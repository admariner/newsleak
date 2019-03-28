package uhh_lt.newsleak.resources;

import java.io.File;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.uima.fit.component.Resource_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.util.Level;
import org.apache.uima.util.Logger;
import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.danishStemmer;
import org.tartarus.snowball.ext.dutchStemmer;
import org.tartarus.snowball.ext.englishStemmer;
import org.tartarus.snowball.ext.finnishStemmer;
import org.tartarus.snowball.ext.frenchStemmer;
import org.tartarus.snowball.ext.germanStemmer;
import org.tartarus.snowball.ext.hungarianStemmer;
import org.tartarus.snowball.ext.italianStemmer;
import org.tartarus.snowball.ext.norwegianStemmer;
import org.tartarus.snowball.ext.portugueseStemmer;
import org.tartarus.snowball.ext.romanianStemmer;
import org.tartarus.snowball.ext.russianStemmer;
import org.tartarus.snowball.ext.spanishStemmer;
import org.tartarus.snowball.ext.swedishStemmer;
import org.tartarus.snowball.ext.turkishStemmer;

import uhh_lt.newsleak.annotator.LanguageDetector;

/**
 * Provides shared functionality and data for the @see
 * uhh_lt.newsleak.annotator.DictionaryExtractor such as reading in dictionary
 * files for each language, and perform stemming of dictionary entries.
 * 
 * Dictionaries shoudl follow the convention of file names providing the
 * dictionary type as main name and ISO-639-3 language code as file extension.
 * Dictionaries containing entries for all languages may have the file extension
 * 'all'. Dictionary files should be placed in <i>conf/dictionaries</i>.
 * 
 * Dictionary files should contain one entry per line. Entries can be single
 * terms, which then are stemmed before comparison with the target data (if a
 * stemmer for the selected language is available). Entries can also be multi
 * word unit (MWU). For MWU, no stemming is performed. MWU are matched via regex
 * instead.
 */
public class DictionaryResource extends Resource_ImplBase {

	/** The logger. */
	private Logger logger;

	/** The Constant PARAM_DATADIR. */
	public static final String PARAM_DATADIR = "dictionaryDir";

	/** The dictionary dir. */
	@ConfigurationParameter(name = PARAM_DATADIR, mandatory = true)
	private String dictionaryDir;

	/** The Constant PARAM_DICTIONARY_FILES. */
	public static final String PARAM_DICTIONARY_FILES = "dictionaryFilesString";

	/** The dictionary files string. */
	@ConfigurationParameter(name = PARAM_DICTIONARY_FILES)
	private String dictionaryFilesString;

	/** The dictionary files. */
	private List<File> dictionaryFiles;

	/** The Constant PARAM_LANGUAGE_CODE. */
	public static final String PARAM_LANGUAGE_CODE = "languageCode";

	/** The language code. */
	@ConfigurationParameter(name = PARAM_LANGUAGE_CODE)
	private String languageCode;

	/** The stemmer. */
	private SnowballStemmer stemmer;

	/** The locale. */
	private Locale locale;

	/** The unigram dictionaries. */
	private HashMap<String, Dictionary> unigramDictionaries;

	/** The mwu dictionaries. */
	private HashMap<String, Dictionary> mwuDictionaries;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.uima.fit.component.Resource_ImplBase#initialize(org.apache.uima.
	 * resource.ResourceSpecifier, java.util.Map)
	 */
	@Override
	public boolean initialize(ResourceSpecifier aSpecifier, Map<String, Object> aAdditionalParams)
			throws ResourceInitializationException {
		if (!super.initialize(aSpecifier, aAdditionalParams)) {
			return false;
		}

		this.logger = this.getLogger();
		locale = LanguageDetector.localeToISO().get(languageCode);

		dictionaryFiles = getDictionaryFiles(dictionaryFilesString);

		// select stemmer
		switch (languageCode) {
		case "eng":
			stemmer = new englishStemmer();
			break;
		case "dan":
			stemmer = new danishStemmer();
			break;
		case "deu":
			stemmer = new germanStemmer();
			break;
		case "nld":
			stemmer = new dutchStemmer();
			break;
		case "fin":
			stemmer = new finnishStemmer();
			break;
		case "fra":
			stemmer = new frenchStemmer();
			break;
		case "hun":
			stemmer = new hungarianStemmer();
			break;
		case "ita":
			stemmer = new italianStemmer();
			break;
		case "nor":
			stemmer = new norwegianStemmer();
			break;
		case "por":
			stemmer = new portugueseStemmer();
			break;
		case "ron":
			stemmer = new romanianStemmer();
			break;
		case "rus":
			stemmer = new russianStemmer();
			break;
		case "spa":
			stemmer = new spanishStemmer();
			break;
		case "swe":
			stemmer = new swedishStemmer();
			break;
		case "tur":
			stemmer = new turkishStemmer();
			break;
		default:
			stemmer = new noStemmer();
		}

		// populate dictionary objects from files
		unigramDictionaries = new HashMap<String, Dictionary>();
		mwuDictionaries = new HashMap<String, Dictionary>();

		for (File f : dictionaryFiles) {

			try {
				String dictType = f.getName().replaceAll("\\..*", "").toUpperCase();
				List<String> dictTermList = FileUtils.readLines(f);
				Dictionary dictUnigrams = new Dictionary();
				Dictionary dictMwu = new Dictionary();
				for (String term : dictTermList) {
					String t = term.trim();
					if (!t.isEmpty()) {

						if (isMultiWord(t)) {
							// handle dictionary entry as multiword unit
							dictMwu.put("(?i)" + Pattern.quote(t), t);
						} else {
							// handle dictionary entry as unigram
							String stem;
							synchronized (stemmer) {
								stemmer.setCurrent(t);
								stemmer.stem();
								stem = stemmer.getCurrent().toLowerCase();
							}

							// map stems to shortest original type
							String shortestType;
							if (dictUnigrams.containsKey(stem) && dictUnigrams.get(stem).length() < t.length()) {
								shortestType = dictUnigrams.get(stem);
							} else {
								shortestType = t;
							}
							dictUnigrams.put(stem, shortestType);
						}

					}
				}
				unigramDictionaries.put(dictType, dictUnigrams);
				mwuDictionaries.put(dictType, dictMwu);

			} catch (IOException e) {
				throw new ResourceInitializationException(e.getMessage(), null);
			}

		}

		return true;
	}

	/**
	 * Checks if a String is a multi word unit.
	 *
	 * @param t
	 *            the t
	 * @return true, if is multi word
	 */
	private boolean isMultiWord(String t) {
		BreakIterator tokenBreaker = BreakIterator.getWordInstance(locale);
		tokenBreaker.setText(t);

		// count tokens
		int pos = tokenBreaker.first();
		int nTokens = 0;
		while (pos != BreakIterator.DONE) {
			nTokens++;
			pos = tokenBreaker.next();
		}
		nTokens = nTokens / 2;
		return nTokens > 1;
	}

	/**
	 * Retrieves the dictionary files as configured in the preprocessing configuration.
	 *
	 * @param list
	 *            the list
	 * @return the dictionary files
	 */
	private List<File> getDictionaryFiles(String list) {
		List<File> files = new ArrayList<File>();
		for (String f : list.split(", +?")) {
			String[] args = f.split(":");
			if (args.length > 2) {
				logger.log(Level.SEVERE,
						"Could not parse dictionary files configuration: '" + list + "'\n"
								+ "Expecting format 'dictionaryfiles = langcode:filename1, langcode:filename2, ...'.\n"
								+ "You can also omit 'langcode:' to apply dictionary to all languages.");
				System.exit(1);
			}
			if (args.length == 1 || (args.length == 2 && args[0].equals(languageCode))) {
				String fname = args.length == 1 ? args[0] : args[1];
				files.add(new File(dictionaryDir, fname));
				logger.log(Level.INFO, "Applying dictionary file " + f + " to language " + languageCode);
			}
		}
		return files;
	}

	/**
	 * Gets the unigram dictionaries.
	 *
	 * @return the unigram dictionaries
	 */
	public HashMap<String, Dictionary> getUnigramDictionaries() {
		return unigramDictionaries;
	}

	/**
	 * A do nothing stemmer.
	 */
	private class noStemmer extends org.tartarus.snowball.SnowballStemmer {

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.tartarus.snowball.SnowballStemmer#stem()
		 */
		@Override
		public boolean stem() {
			return true;
		}

	}

	/**
	 * Stems an input token.
	 *
	 * @param token
	 *            the token
	 * @return the string
	 */
	public synchronized String stem(String token) {
		stemmer.setCurrent(token);
		stemmer.stem();
		return stemmer.getCurrent();
	}

	/**
	 * The Class Dictionary.
	 */
	public class Dictionary extends HashMap<String, String> {

		/** Serial ID. */
		private static final long serialVersionUID = -4395683941205467020L;

		/**
		 * Instantiates a new dictionary.
		 */
		public Dictionary() {
			super();
		}

	}

	/**
	 * Gets the mwu dictionaries.
	 *
	 * @return the mwu dictionaries
	 */
	public HashMap<String, Dictionary> getMwuDictionaries() {
		return mwuDictionaries;
	}

}
