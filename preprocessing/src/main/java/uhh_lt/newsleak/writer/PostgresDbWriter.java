package uhh_lt.newsleak.writer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ExternalResource;
import org.apache.uima.fit.descriptor.OperationalProperties;
import org.apache.uima.fit.util.FSCollectionFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Level;
import org.apache.uima.util.Logger;

import opennlp.uima.Location;
import opennlp.uima.Organization;
import opennlp.uima.Person;
import uhh_lt.newsleak.resources.PostgresResource;
import uhh_lt.newsleak.types.DictTerm;
import uhh_lt.newsleak.types.Metadata;

@OperationalProperties(multipleDeploymentAllowed=true, modifiesCas=false)
public class PostgresDbWriter extends JCasAnnotator_ImplBase {

	private Logger logger;

	public static final String RESOURCE_POSTGRES = "postgresResource";
	@ExternalResource(key = RESOURCE_POSTGRES)
	private PostgresResource postgresResource;

	private NewsleakTimeFormatter timeFormatter;

	@Override
	public void initialize(UimaContext context) throws ResourceInitializationException {
		super.initialize(context);
		logger = context.getLogger();
		timeFormatter = new NewsleakTimeFormatter();
	}


	@Override
	public void collectionProcessComplete() throws AnalysisEngineProcessException {
		super.collectionProcessComplete();
		// commit final inserts/updates
		postgresResource.commit();
	}

	@Override
	public void process(JCas jcas) throws AnalysisEngineProcessException {

		Metadata metadata = (Metadata) jcas.getAnnotationIndex(Metadata.type).iterator().next();
		Integer docId = Integer.parseInt(metadata.getDocId());

		try {

			// documents
			String docText = jcas.getDocumentText().replaceAll("\r", "");
			String docDate = metadata.getTimestamp();
			postgresResource.insertDocument(docId, docText, docDate);

			// entities and offsets
			Collection<Person> persons = JCasUtil.select(jcas, Person.class);
			processEntities(persons, "PER", docId);
			Collection<Organization> orgs = JCasUtil.select(jcas, Organization.class);
			processEntities(orgs, "ORG", docId);
			Collection<Location> locs = JCasUtil.select(jcas, Location.class);
			processEntities(locs, "LOC", docId);
			
			// dictionary entities
			HashMap<String, HashSet<DictTerm>> dictAnnotations = new HashMap<String, HashSet<DictTerm>>();
			HashMap<String, HashMap<String, String>> baseFormMap = new HashMap<String, HashMap<String, String>>();
			Collection<DictTerm> dictTerms = JCasUtil.select(jcas, DictTerm.class);
			for (DictTerm dictTerm : dictTerms) {
				Collection<String> typeList = FSCollectionFactory.create(dictTerm.getDictType());
				int i = 0;
				for (String type : typeList) {
					HashSet<DictTerm> typeTerms = dictAnnotations.containsKey(type) ? 
							dictAnnotations.get(type) : new HashSet<DictTerm>();
					HashMap<String, String> baseForms = baseFormMap.containsKey(type) ? 
							baseFormMap.get(type) : new HashMap<String, String>();
					typeTerms.add(dictTerm);
					baseForms.put(dictTerm.getCoveredText(), dictTerm.getDictTerm().getNthElement(i));
					i++;
					dictAnnotations.put(type, typeTerms);
					baseFormMap.put(type, baseForms);
				}
			}
			for (String type : dictAnnotations.keySet()) {
				processEntities(dictAnnotations.get(type), type, docId, baseFormMap.get(type));
			}

			
			
			// eventtime
			ArrayList<String> extractedTimes = timeFormatter.format(jcas);
			if (extractedTimes.size() > 0) {
				for (String line : extractedTimes) {
					String[] items = line.split("\t");
					try {
						String formattedDate = timeFormatter.filterDate(items[4]);
						if (formattedDate != null) {
							postgresResource.insertEventtime(docId, Integer.parseInt(items[0]), Integer.parseInt(items[1]), items[2], items[3], formattedDate);
						}
					}
					catch (Exception e) {
						System.out.println(items);
					}
				}
			}
			
			// terms
			String keytermList = metadata.getKeyterms();
			if (keytermList != null) {
				for (String item : metadata.getKeyterms().split("\t")) {
					String[] termFrq = item.split(":");
					if (termFrq.length == 2) {
						postgresResource.insertKeyterms(docId, termFrq[0], Integer.parseInt(termFrq[1]));
					}
				}
			}			
			
			// execute batches
			postgresResource.executeBatches();


		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Could not write document " + docId);
			e.printStackTrace();
			System.exit(1);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		
	}
	
	
	private void processEntities(Collection<? extends Annotation> matches, String type, Integer docId) throws SQLException {
		processEntities(matches, type, docId, null);
	}

	private void processEntities(Collection<? extends Annotation> matches, String type, Integer docId, HashMap<String, String> baseForms) throws SQLException {
		HashMap<String, Integer> counter = new HashMap<String, Integer>();
		HashMap<String, ArrayList<Annotation>> offsets = new HashMap<String, ArrayList<Annotation>>();
		for (Annotation annotation : matches) {
			String entity;
			if (baseForms == null) {
				entity = annotation.getCoveredText();
			} else {
				String coveredText =  annotation.getCoveredText();
				entity = baseForms.containsKey(coveredText) ? baseForms.get(coveredText) : coveredText;
			}
			counter.put(entity, counter.containsKey(entity) ? counter.get(entity) + 1 : 1);
			if (offsets.containsKey(entity)) {
				offsets.get(entity).add(annotation);
			} else {
				ArrayList<Annotation> l = new ArrayList<Annotation>();
				l.add(annotation);
				offsets.put(entity, l);
			}
		}
		for (String entity : counter.keySet()) {
			Integer entityId = postgresResource.insertEntity(entity, type, counter.get(entity));
			for (Annotation annotation : offsets.get(entity)) {
				postgresResource.insertEntityoffset(docId, entityId, annotation.getBegin(), annotation.getEnd());
			}
		}
	}

}
