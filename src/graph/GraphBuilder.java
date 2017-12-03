package graph;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;

import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.JSONOutputter;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.EnhancedPlusPlusDependenciesAnnotation;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.PropertiesUtils;

public class GraphBuilder implements AutoCloseable {
  private StanfordCoreNLP pipeline;
  private final Driver driver;
  private String docId = null;
  
  private static int uid = 0;
  
  /**
   * Constructor
   * @param uri - uri for neo4j
   * @param user - neo4j username
   * @param password - neo4j password
   */
  public GraphBuilder(String uri, String user, String password) {
    this.pipeline = new StanfordCoreNLP(
        PropertiesUtils.asProperties(
            "annotators", "tokenize,ssplit,pos,lemma,parse",
            "ssplit.boundaryTokenRegex", "\\.|;|\n"));
    this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
  }
  
  /**
   * Build the graphs from xml file.
   * @param sourceName - xml filename
   * @param outputName - Stanford CoreNLP output in json format for debugging
   * purpose
   * @throws JSONException
   * @throws IOException
   */
  public void build(String sourceName, String outputName) throws
  JSONException, IOException {
    String xmlStr = this.loadXML(sourceName).replaceAll("\\s+[1-9]\\.", "");
    xmlStr = xmlStr.replaceAll("\\s+", " ");
    JSONObject xmlJSONObj = XML.toJSONObject(xmlStr);
    JSONObject annot = new JSONObject();
    this.docId = xmlJSONObj.getJSONObject("clinical_study").
        getJSONObject("id_info").getString("nct_id");

    this.DFS(xmlJSONObj, null, annot);
    try(BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new 
        FileOutputStream(outputName)))) {
       writer.write(xmlJSONObj.toString());
    }
    try(BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new 
        FileOutputStream("annotated_" + outputName)))) {
       writer.write(annot.toString());
    }
  }
  
  private String loadXML(String filename) {
    StringBuilder sb = new StringBuilder();
    try(BufferedReader reader = new BufferedReader(new InputStreamReader(new
        FileInputStream(filename), StandardCharsets.UTF_8))) {
      String line = null;
      while((line = reader.readLine()) != null)
        sb.append(line);
      return sb.toString();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }
  
  private void DFS(JSONObject jsonObj, String objKey, JSONObject annot) throws
  JSONException, IOException {
    Iterator<?> it = jsonObj.keys();
    while(it.hasNext()) {
      String key = (String) it.next();
      if(key.equals("brief_summary")) continue;
      if(key.equals("criteria")) {
        JSONObject criteria = jsonObj.getJSONObject(key);
        String textblock = criteria.getString("textblock");
        textblock = this.replaceIllegalChars(textblock);
        int sepIndex = textblock.indexOf("Exclusion Criteria");
        if(sepIndex == -1) {
          this.DFS(criteria, key, annot);
          continue;
        }
        String inclCri = textblock.substring(textblock.indexOf(':') + 2, 
            sepIndex);
        Annotation document = new Annotation(inclCri);
        this.pipeline.annotate(document);
        String results = JSONOutputter.jsonPrint(document);
        if(results == null) throw new JSONException("Fail to get CoreNLP"
            + " response");
        
        annot.put("criteria", new JSONObject());
        annot.getJSONObject("criteria").put("Inclusion Criteria", new
            JSONObject(results));
        
        this.buildAllGraph(document, "Inclusion Criteria");
        
        String exclCri = textblock.substring(sepIndex);
        exclCri = exclCri.substring(exclCri.indexOf(':') + 2);
        document = new Annotation(exclCri);
        this.pipeline.annotate(document);
        results = JSONOutputter.jsonPrint(document);
        
        if(results == null) throw new JSONException("Fail to get CoreNLP "
            + "response");
        
        annot.getJSONObject("criteria").put("Exclusion Criteria", new
            JSONObject(results));
        this.buildAllGraph(document, "Exclusion Criteria");
      } else if (key.equals("textblock") || key.equals("description")) {
        String text = jsonObj.getString(key);
        text = this.replaceIllegalChars(text);
        Annotation document = new Annotation(text);
        this.pipeline.annotate(document);
        String results = JSONOutputter.jsonPrint(document);
        if(results == null) throw new JSONException("Fail to get CoreNLP"
            + " response");
        annot.put(objKey, new JSONObject());
        annot.getJSONObject(objKey).put(key, new JSONObject(results));
        this.buildAllGraph(document, objKey);
      } else if(jsonObj.get(key) instanceof JSONObject)
        this.DFS(jsonObj.getJSONObject(key), key, annot);
      else if(jsonObj.get(key) instanceof JSONArray)
        this.DFS(jsonObj.getJSONArray(key), key, annot);
    }
  }
  
  private void DFS(JSONArray jsonArray, String arrayKey, JSONObject annot) 
      throws JSONException, IOException {
    for(int i = 0; i < jsonArray.length(); i++) {
      if(jsonArray.get(i) instanceof JSONObject)
        this.DFS(jsonArray.getJSONObject(i), arrayKey, annot);
      else return;
    }
  }

  @Override
  public void close() throws Exception {
    this.driver.close();
  }
  
  private void buildAllGraph(Annotation annot, String section) {
    List<CoreMap> sentences = annot.get(SentencesAnnotation.class);
    int index = 0;
    for(CoreMap sentence: sentences) {
      Tree tree = sentence.get(TreeAnnotation.class);
      tree.setSpans();
      List<CoreLabel> map = sentence.get(TokensAnnotation.class);
      this.buildTree(tree, map, index, section);
      index++;
      
      SemanticGraph dependencies = sentence.get
          (EnhancedPlusPlusDependenciesAnnotation.class);
      List<SemanticGraphEdge> list = dependencies.edgeListSorted();
      this.buildDependency(list, section, index);
    }
  }
  
  private String replaceIllegalChars(String text) {
    text = text.replace("≦", "<=");
    text = text.replace("≤", "<=");
    text = text.replace("≧", ">=");
    text = text.replace("≥", ">=");
    text = text.replace("®", "(R)");
    text = text.replace("- ", "\n\n");
    return text;
  }
  
  private void buildTree(Tree root, List<CoreLabel> map, int index, String
      section) {
    Iterator<Tree> it = root.iterator();
    HashMap<Tree, String> idMap = new HashMap<>();
    while(it.hasNext()) {
      Tree curr = it.next();
      String nodeId = this.createNode(curr, map, index, section);
      idMap.put(curr, nodeId);
      if(curr.value().equals("ROOT")) continue;
      boolean rootEdge = curr.ancestor(1, root).value().equals("ROOT") ?
          true : false;
      this.createRelation(idMap.get(curr.ancestor(1, root)), nodeId, rootEdge);
    }
  }
  
  private String createNode(Tree node, List<CoreLabel> map, int index, String
      section) {
    int tmp = uid;
    try (Session session = driver.session()) {
      String nodeType = ":ParseNode";
      if(node.isLeaf())
        nodeType = ":TextNode";
      int beginOffset = map.get(node.getSpan().getSource()).beginPosition();
      int endOffset = map.get(node.getSpan().getTarget()).endPosition() - 1;
      if(node.value().equals("ROOT"))
        session.run("MERGE (n" + nodeType + ":" + "`" + node.value() + "`"
      + " { section: " + "'" + section + "', " + "sentenceNum: " + "'" + index
      + "', " + "DocID:" + " " + "'" + this.docId + "', " + "StartOffset: " +
      "'" + beginOffset + "', " + "EndOffset: " + "'" + endOffset + "', " +
      "uid: " + "'" + uid++ + "' })");
      else if(!node.isLeaf())
        session.run("MERGE (n" + nodeType + ":" + "`" + node.value() + "`" + 
            " { StartOffset: " + "'" + beginOffset + "', " + "EndOffset: "
            + "'" + endOffset + "', " + "uid: " + "'" + uid++ + "' })");
      else
        session.run("MERGE (n" + nodeType + " { TextValue: " + "'" +
      node.value().replace("'", "") + "', " + "StartOffset: " + "'" + 
            beginOffset + "', " + "EndOffset: " + "'" + endOffset + "', "
                + "" + "uid: " + "'" + uid++ + "' })");
    }
    return tmp + "";
  }
  
  private void createRelation(String sourceID, String targetID, boolean 
      rootEdge) {
    try (Session session = driver.session()) {
      if(rootEdge)
    	  session.run("MATCH (a) WHERE a.uid = '" + sourceID + "' MATCH (b) "
    	      + "WHERE b.uid = '" + targetID + "' MERGE (a)-[:RootEdge]->(b)");
      else
        session.run("MATCH (a) WHERE a.uid = '" + sourceID + "' MATCH (b) "
            + "WHERE b.uid = '" + targetID + "' MERGE (a)-[:ParseEdge]->(b)");
    }
  }
  
  private void buildDependency(List<SemanticGraphEdge> edgeList, String 
      section, int index) {
    ListIterator<SemanticGraphEdge> it = edgeList.listIterator();
    HashMap<Integer, Integer> map = new HashMap<>();
    while(it.hasNext()) {
      SemanticGraphEdge edge = it.next();
      String source = edge.getSource().value();
      int sourceIdx = edge.getSource().index();
      if(!map.containsKey(new Integer(sourceIdx)))
          map.put(sourceIdx, uid++);
      String target = edge.getTarget().value();
      int targetIdx = edge.getTarget().index();
      if(!map.containsKey(new Integer(targetIdx)))
        map.put(targetIdx, uid++);
      String relation = edge.getRelation().toString();
      try (Session session = driver.session()) {
        session.run("MERGE (a:WordNode { word : \"" + target + "\", idx : '" + 
            targetIdx + "', uid : '" + map.get(new Integer(targetIdx)).
            intValue() + "' }) MERGE (b:WordNode { word: \"" + source + 
            "\", idx : '" + sourceIdx + "', uid : '" + map.get(new 
                Integer(sourceIdx)).intValue()+ "' }) MERGE (a)-[:`" +
            relation + "`]->(b)");
      }
      
      if(!it.hasNext()) {
        try (Session session = driver.session()) {
          session.run("MERGE (a:WordNode { word : '" + source + "', idx : '" + 
              sourceIdx + "', uid : '" + map.get(new Integer(sourceIdx)).intValue()
              + "' }) MERGE (b:WordNode { word: 'ROOT', DocID : ' " + 
              this.docId + "', section : '" + section + "', sentenceNum : '"
              + index + "', idx : '0', uid : '" + uid++ + 
              "' }) MERGE (a)-[:root]->(b)");
        }
      }
    }
  }
  
  public static void main(String args[]) {
    try (GraphBuilder builder = new GraphBuilder("bolt://localhost:7687",
        "neo4j", "Fchgj10%")){
    	File dir = new File("xml");
    	for(final File file : dir.listFiles())
    		builder.build(file.getPath(), file.getName() + ".json");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}