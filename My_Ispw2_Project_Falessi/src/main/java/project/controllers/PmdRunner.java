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
     * Runs PMD analysis on a file or directory
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
     * Extracts only the class name from a full class name.
     * Removes the package, module and any reference to inner or anonymous classes.
     *
     * @param fullClassName the full class name (e.g. "org.example.benchmark.MyClass$InnerClass")
     * @return only the class name (e.g. "MyClass")
     */
    public static String extractClassNameOnly(String fullClassName) {
        int dollarIndex = fullClassName.indexOf('$');
        String cleanName = (dollarIndex != -1) ? fullClassName.substring(0, dollarIndex) : fullClassName;

        // Extract only the class name without package/module
        int lastDot = cleanName.lastIndexOf('.');
        return (lastDot != -1) ? cleanName.substring(lastDot + 1) : cleanName;
    }



    public static int collectCodeSmellMetricsClass(String classPath, String projectPath, int startLine, int endLine) {
        String className = extractClassNameOnly(classPath);
        int nSmells = 0;

        try {
            String javaFile = findClassFile(className, projectPath);
            if (javaFile == null) {
                LOGGER.error("File not found for class: {}" , className);
                return 0;
            }

            Report report = runPmdAnalysis(Path.of(javaFile));

            Set<String> ruleNames = getRuleNamesFromXml(RULES_SET_PATH_STRING);

            // Initialize metrics for this class
            Map<String, Integer> metrics = new HashMap<>();
            for (String rule : ruleNames) {
                metrics.put(rule, 0);
            }



            // Count occurrences of each violation type
            Iterator<RuleViolation> violations = report.iterator();
            while (violations.hasNext()) {
                RuleViolation violation = violations.next();
                int line = violation.getBeginLine();
                String ruleName = violation.getRule().getName();

                // Filter violations between startLine and endLine (inclusive)
                if (line >= startLine && line <= endLine && metrics.containsKey(ruleName) ) {
                        metrics.put(ruleName, metrics.get(ruleName) + 1);
                        nSmells++;
                }
            }



        } catch (Exception e) {
            LOGGER.error("Error analyzing class {} : {} " ,className, e.getMessage());
            nSmells=-1;
        }

        return nSmells;
    }
    /**
     * Extracts rule names from the specified XML file
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
            LOGGER.error("Error extracting rules from XML file: {}" , e.getMessage());
        }
        return ruleNames;
    }




     /**
     * Finds the Java file corresponding to the specified class name
     * using a manual approach that consumes fewer system resources.
     *
     * @param className the class name to search for
     * @param projectPath the project path to search in
     * @return the full path of the class file, or null if not found
     */
    private static String findClassFile(String className, String projectPath) {
        String targetFileName = className + ".java";

        // Queue for BFS
        Queue<File> directories = new LinkedList<>();
        directories.add(new File(projectPath));

        // Avoid useless directories (for performance)
        Set<String> ignoredDirs = Set.of("target", ".git", "build", "out", "node_modules");

        // Maximum depth (e.g. avoid infinite or too deep explorations)
        final int MAX_DEPTH = 20;

        // Map directory -> depth
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
