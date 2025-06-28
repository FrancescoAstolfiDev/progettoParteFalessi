package project.controllers;

import net.sourceforge.pmd.*;
import net.sourceforge.pmd.renderers.Renderer;
import net.sourceforge.pmd.renderers.TextRenderer;
import net.sourceforge.pmd.util.datasource.DataSource;
import net.sourceforge.pmd.util.datasource.FileDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import project.statefull.ConstantsWindowsFormat;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.Path;
import java.util.*;


public class PmdRunner {

    PmdRunner(){}
    private static final Logger LOGGER = LoggerFactory.getLogger(PmdRunner.class);

    static final String RULES_SET_PATH_STRING = ConstantsWindowsFormat.RULES_SET_PATH.toString();
    /**
     * Esegue l'analisi PMD su un file o directory
     */
    public static Report runPmdAnalysis(Path sourceFilePath) throws IOException {
        PMDConfiguration configuration = new PMDConfiguration();
        List<String> ruleSets = new ArrayList<>();
        ruleSets.add(RULES_SET_PATH_STRING);
        configuration.setInputFilePath(sourceFilePath);
        configuration.setRuleSets(ruleSets);

        configuration.setIgnoreIncrementalAnalysis(true);

        RuleContext context = new RuleContext();

        try (StringWriter reportOutput = new StringWriter()) {
            Renderer renderer = new TextRenderer();
            renderer.setWriter(reportOutput);
            renderer.start();

            File sourceFile = sourceFilePath.toFile();
            try (InputStream inputStream = new FileInputStream(sourceFile)) {
                DataSource dataSource = new FileDataSource(sourceFile);
                List<DataSource> files = Collections.singletonList(dataSource);

                RuleSetFactory ruleSetFactory = new RuleSetFactory();

                PMD.processFiles(configuration, ruleSetFactory, files, context, Collections.singletonList(renderer));
            }

            renderer.end();
            renderer.flush();
            renderer.getWriter().close();



            return context.getReport();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Estrae solo il nome della classe da un nome di classe completo.
     * Rimuove il package, il modulo e qualsiasi riferimento a classi interne o anonime.
     *
     * @param fullClassName il nome completo della classe (es. "org.example.benchmark.MyClass$InnerClass")
     * @return solo il nome della classe (es. "MyClass")
     */
    public static String extractClassNameOnly(String fullClassName) {
        int dollarIndex = fullClassName.indexOf('$');
        String cleanName = (dollarIndex != -1) ? fullClassName.substring(0, dollarIndex) : fullClassName;

        // Estrae solo il nome della classe senza package/modulo
        int lastDot = cleanName.lastIndexOf('.');
        return (lastDot != -1) ? cleanName.substring(lastDot + 1) : cleanName;
    }



    public static int collectCodeSmellMetricsClass(String classPath, String projectPath, int startLine, int endLine) {
        String className = extractClassNameOnly(classPath);
        int nSmells = 0;

        try {
            String javaFile = findClassFile(className, projectPath);
            if (javaFile == null) {
                LOGGER.error("File non trovato per la classe: {}" , className);
                return 0;
            }

            Report report = runPmdAnalysis(Path.of(javaFile));

            Set<String> ruleNames = getRuleNamesFromXml(RULES_SET_PATH_STRING);

            // Inizializza le metriche per questa classe
            Map<String, Integer> metrics = new HashMap<>();
            for (String rule : ruleNames) {
                metrics.put(rule, 0);
            }



            // Conta le occorrenze di ogni tipo di violazione
            Iterator<RuleViolation> violations = report.iterator();
            while (violations.hasNext()) {
                RuleViolation violation = violations.next();
                int line = violation.getBeginLine();
                String ruleName = violation.getRule().getName();

                // Filtra le violazioni tra startLine e endLine (inclusi)
                if (line >= startLine && line <= endLine && metrics.containsKey(ruleName) ) {
                        metrics.put(ruleName, metrics.get(ruleName) + 1);
                        nSmells++;
                }
            }



        } catch (Exception e) {
            LOGGER.error("Errore nell'analisi della classe {} : {} " ,className, e.getMessage());
            nSmells=-1;
        }

        return nSmells;
    }
    /**
     * Estrae i nomi delle regole dal file XML specificato
     */
    private static Set<String> getRuleNamesFromXml(String rulesFilePath) {
        Set<String> ruleNames = new HashSet<>();
        try (InputStream inputStream = new FileInputStream(rulesFilePath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);

            NodeList ruleElements = document.getElementsByTagName("rule");
            for (int i = 0; i < ruleElements.getLength(); i++) {
                Element ruleElement = (Element) ruleElements.item(i);
                String refAttribute = ruleElement.getAttribute("ref");
                if (refAttribute != null && !refAttribute.isEmpty()) {
                    String[] parts = refAttribute.split("/");
                    String ruleName = parts[parts.length - 1];
                    ruleNames.add(ruleName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Errore durante l'estrazione delle regole dal file XML:  {}" , e.getMessage());
        }
        return ruleNames;
    }




    /**
     * Trova il file Java corrispondente al nome della classe specificato
     * @param className il nome della classe da cercare
     * @param projectPath il percorso del progetto in cui cercare
     * @return il percorso completo del file della classe, o null se non trovato
     */
    /**
     * Trova il file Java corrispondente al nome della classe specificato
     * usando un approccio manuale che consuma meno risorse di sistema.
     *
     * @param className il nome della classe da cercare
     * @param projectPath il percorso del progetto in cui cercare
     * @return il percorso completo del file della classe, o null se non trovato
     */
    private static String findClassFile(String className, String projectPath) {
        String targetFileName = className + ".java";

        // Coda per BFS
        Queue<File> directories = new LinkedList<>();
        directories.add(new File(projectPath));

        // Evita directory non utili (per performance)
        Set<String> ignoredDirs = Set.of("target", ".git", "build", "out", "node_modules");

        // Profondità massima (es. evita esplorazioni infinite o troppo profonde)
        final int MAX_DEPTH = 20;

        // Mappa directory -> profondità
        Map<File, Integer> depthMap = new HashMap<>();
        depthMap.put(new File(projectPath), 0);

        while (!directories.isEmpty()) {
            File currentDir = directories.poll();
            int currentDepth = depthMap.getOrDefault(currentDir, 0);
            File[] files = currentDir.listFiles();
            if (currentDepth > MAX_DEPTH || files==null) {
                continue;
            }

            for (File file : files) {
                if (file.isDirectory()) {
                    if (!ignoredDirs.contains(file.getName())) {
                        directories.add(file);
                        depthMap.put(file, currentDepth + 1);
                    }
                } else if (file.isFile() && file.getName().equals(targetFileName)) {
                    return file.getAbsolutePath();
                }
            }
        }

        return null;
    }
}