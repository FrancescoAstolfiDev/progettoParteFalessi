package project.controllers;

import project.models.DataSetType;
import project.models.ResultsHolder;
import project.utils.ConstantsWindowsFormat;
import project.utils.CostumException;
import weka.classifiers.Classifier;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.CostMatrix;
import weka.classifiers.Evaluation;
import weka.classifiers.functions.MultilayerPerceptron;
import weka.core.Attribute;
import weka.classifiers.functions.SGD;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SpreadSubsample;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.System.*;

public class EvaluationFlow {

    // Messaggi di log come variabili di classe
    private static final String THREAD_POOL_MSG = "Using thread pool with %d threads for parallel processing";
    private static final String ERROR_PROCESSING_RELEASE_MSG = "Error processing release %d: %s";
    private static final String ERROR_LOADING_ARFF_MSG = "Error loading ARFF files for release %d: %s";
    private static final String WARNING_EMPTY_TRAIN_SET_MSG = "Warning: Empty training set for release %d. Skipping %s evaluation.";
    private static final String WARNING_EMPTY_TEST_SET_MSG = "Warning: Empty test set for release %d. Skipping %s evaluation.";
    private static final String WARNING_INVALID_CLASS_INDEX_MSG = "Warning: Invalid class index in %s set for release %d. Setting to last attribute.";
    private static final String WARNING_NOT_ENOUGH_INSTANCES_MSG = "Warning: Not enough instances for %s training in release %d%s";
    private static final String ERROR_CLASSIFIER_EVALUATION_MSG = "Error in %s evaluation for release %d: %s";
    private static final String WARNING_UNDERSAMPLING_RESULT_MSG = "Warning: Under-sampling resulted in empty dataset for release %d. Skipping.";
    private static final String WARNING_OVERSAMPLING_RESULT_MSG = "Warning: Oversampling resulted in empty dataset for release %d. Skipping.";
    private static final String WARNING_FEATURE_SELECTION_RESULT_MSG = "Warning: Feature selection resulted in %s for release %d. Skipping.";
    private static final String WARNING_TOO_FEW_ATTRIBUTES_MSG = "Warning: Feature selection resulted in too few attributes for release %d. Skipping.";
    private static final String FEATURE_SELECTION_LOG_MSG = "\n=== Feature Selection Results for Release %d ===\n";
    private static final String CSV_SUCCESS_MSG = "File CSV creato con successo.";
    private static final String CSV_ERROR_MSG = "Si è verificato un errore durante la creazione del file CSV: %s";
    private static final String RELEASE_NUMBER_WARNING_MSG = "Warning: Could not extract release number from relation name: %s";
    private static final String CACHED_FILTER_MSG = "Using cached feature selection filter for release %d";
    private static final String NEW_FILTER_MSG = "Creating new feature selection filter%s";
    private static final String FEATURE_LOG_WRITTEN_MSG = "Feature selection log written to %s";
    private static final String FEATURE_LOG_ERROR_MSG = "Error writing feature selection log to file: %s";
    private static final String EXPENSIVE_FEATURE_SELECTION="expensive feature selection";
    private static final String FEATURE_SELECTION="feature selection";
    private static final String OVERSAMPLING="oversampling";
    private static final String UNDER_SAMPLING="under sampling";
    
    RandomForest randomForestClassifier;
    MultilayerPerceptron multilayerPerceptronClassifier;
    SGD sgdClassifier;
    String projectName;
    // Cache for feature selection filters to avoid redundant computation
    private final java.util.Map<Integer, AttributeSelection> featureSelectionCache = new java.util.HashMap<>();
    List<ResultsHolder> standardRFList;
    List<ResultsHolder> standardNBList;
    List<ResultsHolder> standardSGDList;
    List<ResultsHolder> costSensitiveRFList;
    List<ResultsHolder> costSensitiveSGDList;
    List<ResultsHolder> costSensitiveNBList;
    List<ResultsHolder> underSamplRFList;
    List<ResultsHolder> underSamplSGDList;
    List<ResultsHolder> underSamplNBList;
    List<ResultsHolder> overSamplRFList;
    List<ResultsHolder> overSamplSGDList;
    List<ResultsHolder> overSamplNBList;
    List<ResultsHolder> featSelRFList;
    List<ResultsHolder> featSelSGDList;
    List<ResultsHolder> featSelNBList;
    List<ResultsHolder> featSelUnderSamplRFList;
    List<ResultsHolder> featSelUnderSamplSGDList;
    List<ResultsHolder> featSelUnderSamplNBList;
    List<ResultsHolder> featSelCostSensRFList;
    List<ResultsHolder> featSelCostSensSGDList;
    List<ResultsHolder> featSelCostSensNBList;

    public EvaluationFlow(String name){
        this.projectName = name;
        //questi sono i classificatori che utilizzo con parametri ottimizzati per velocità
        this.randomForestClassifier = new RandomForest();
        // Optimize RandomForest for speed and stability
        this.randomForestClassifier.setNumIterations(10); // Default is 100, reducing for speed
        this.randomForestClassifier.setMaxDepth(5);  // Limit tree depth for faster training
        this.randomForestClassifier.setNumExecutionSlots(1); // Ensure single-threaded execution for stability

        this.multilayerPerceptronClassifier = new MultilayerPerceptron();
        // Configure MultilayerPerceptron for optimal performance
        this.multilayerPerceptronClassifier.setLearningRate(0.1);
        this.multilayerPerceptronClassifier.setMomentum(0.2);
        this.multilayerPerceptronClassifier.setTrainingTime(500); // Limit training time
        this.multilayerPerceptronClassifier.setHiddenLayers("3"); // Simple network architecture

        this.sgdClassifier = new SGD();
        // Configure SGD for optimal performance
        this.sgdClassifier.setLearningRate(0.01);
        this.sgdClassifier.setEpochs(500); // Number of epochs

        //queste sono le liste che contengono i risultati delle valutazioni per tipologia di classificatore
        this.standardRFList= new ArrayList<>();
        this.standardNBList= new ArrayList<>();
        this.standardSGDList = new ArrayList<>();
        this.costSensitiveRFList = new ArrayList<>();
        this.costSensitiveSGDList = new ArrayList<>();
        this.costSensitiveNBList = new ArrayList<>();
        this.underSamplRFList = new ArrayList<>();
        this.underSamplSGDList = new ArrayList<>();
        this.underSamplNBList = new ArrayList<>();
        this.overSamplRFList = new ArrayList<>();
        this.overSamplSGDList = new ArrayList<>();
        this.overSamplNBList = new ArrayList<>();
        this.featSelSGDList = new ArrayList<>();
        this.featSelNBList = new ArrayList<>();
        this.featSelRFList = new ArrayList<>();
        this.featSelUnderSamplSGDList = new ArrayList<>();
        this.featSelUnderSamplNBList = new ArrayList<>();
        this.featSelUnderSamplRFList = new ArrayList<>();
        this.featSelCostSensSGDList = new ArrayList<>();
        this.featSelCostSensNBList = new ArrayList<>();
        this.featSelCostSensRFList = new ArrayList<>();
    }

    public void executeFlow() {
        try {
            int numRelease = determineNumRelease();
            int threadPoolSize = determineThreadPoolSize();
            out.printf((THREAD_POOL_MSG) + "%n", threadPoolSize);

            try (ExecutorServiceWithAwait executor = new ExecutorServiceWithAwait(threadPoolSize)) {
                List<CompletableFuture<Void>> futures = processReleases(executor, numRelease);
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            }
            writeResults();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Ripristina lo stato di interruzione
            throw new CostumException("Interruzione durante l'evaluation flow", e);
        } catch (ExecutionException e) {
            throw new CostumException("Errore durante l'evaluation flow", e);
        }
    }


    private int determineNumRelease() {
        return Objects.equals(this.projectName, "bookkeeper") ? 4 : 12;
    }

    private int determineThreadPoolSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(2, processors - 1);
    }

    private List<CompletableFuture<Void>> processReleases(ExecutorServiceWithAwait executor, int numRelease) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 3; i <= numRelease; i++) {
            final int releaseIndex = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processRelease(releaseIndex);
                } catch (Exception e) {
                    out.println(String.format(ERROR_PROCESSING_RELEASE_MSG, releaseIndex, e.getMessage()));
                }
            }, executor.getExecutor());
            futures.add(future);
        }
        return futures;
    }


    private void writeResults() {
        List<List<ResultsHolder>> allResults = Arrays.asList(
                standardRFList, standardNBList, standardSGDList,
                costSensitiveRFList, costSensitiveSGDList, costSensitiveNBList,
                underSamplRFList, underSamplSGDList, underSamplNBList,
                overSamplRFList, overSamplSGDList, overSamplNBList,
                featSelRFList, featSelSGDList, featSelNBList,
                featSelUnderSamplRFList, featSelUnderSamplSGDList, featSelUnderSamplNBList,
                featSelCostSensRFList, featSelCostSensSGDList, featSelCostSensNBList
        );
        csvWriter(allResults);
    }

    private static class ExecutorServiceWithAwait implements AutoCloseable {
        private final ExecutorService executor;

        ExecutorServiceWithAwait(int threadPoolSize) {
            this.executor = Executors.newFixedThreadPool(threadPoolSize);
        }

        ExecutorService getExecutor() {
            return executor;
        }

        @Override
        public void close() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // Helper method to process a single release
    private void processRelease(int releaseIndex) throws Exception {
        //recupero i dati dai file .arff
        String trainFileName = this.projectName + "_Train_R" + releaseIndex + ".arff";
        String testFileName = this.projectName + "_Test_R" + releaseIndex + ".arff";
        String trainFilePath = ConstantsWindowsFormat.ARFF_PATH.resolve(trainFileName).toString();
        String testFilePath = ConstantsWindowsFormat.ARFF_PATH.resolve(testFileName).toString();

        try {
            DataSource trainSource = new DataSource(trainFilePath);
            DataSource testSource = new DataSource(testFilePath);
            Instances trainSet = trainSource.getDataSet();
            Instances testSet = testSource.getDataSet();

            //setto il parametro buggy come variabile di interesse
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
            testSet.setClassIndex(testSet.numAttributes() - 1);

            // Process all evaluation methods for this release
            // We could parallelize these too, but they share classifiers which might not be thread-safe
            evalStandard(trainSet, testSet, releaseIndex, false, false, false);
            evalCostSensitive(trainSet, testSet, releaseIndex, false);
            evalUnderSampling(trainSet, testSet, releaseIndex);
            evalOverSampling(trainSet, testSet, releaseIndex);
            evalFeatureSelection(trainSet, testSet, releaseIndex);
            evalUnderSampFeatureSelection(trainSet, testSet, releaseIndex);
            evalCostFeatureSelection(trainSet, testSet, releaseIndex);
        } catch (Exception e) {
            out.println(String.format(ERROR_LOADING_ARFF_MSG, releaseIndex, e.getMessage()));
            throw e; // Rethrow to be caught by the calling method
        }
    }

    //metodo che addestra i classificatori in maniera standard, ovvero senza sampling, feature selection o
    //cost sensitive. Effettua un passo del walk forward per i tre classificatori
    public void evalStandard(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected,
                             boolean isUnderSampled, boolean isOverSampled) {

        if (!validateDatasets(trainSet, testSet, index)) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        // Crea e configura i classificatori thread-local
        Map<String, Classifier> threadLocalClassifiers = createThreadLocalClassifiers();

        // Esegui training e valutazione in parallelo
        List<CompletableFuture<ResultsHolder>> futures = trainAndEvaluateClassifiers(
                threadLocalClassifiers, trainSet, testSet, index, isFeatureSelected, isUnderSampled);

        // Raccogli i risultati
        List<ResultsHolder> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Salva i risultati nelle liste appropriate
        saveResults(results, isFeatureSelected, isUnderSampled, isOverSampled);
    }

    private boolean validateDatasets(Instances trainSet, Instances testSet, int index) {
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, "standard"));
            return false;
        }
        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, "standard"));
            return false;
        }
        return true;
    }

    private void ensureValidClassIndices(Instances trainSet, Instances testSet) {
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }
        if (testSet.classIndex() < 0 || testSet.classIndex() >= testSet.numAttributes()) {
            testSet.setClassIndex(testSet.numAttributes() - 1);
        }
    }

    private Map<String, Classifier> createThreadLocalClassifiers() {
        Map<String, Classifier> classifiers = new HashMap<>();

        RandomForest rf = new RandomForest();
        rf.setNumIterations(this.randomForestClassifier.getNumIterations());
        rf.setMaxDepth(this.randomForestClassifier.getMaxDepth());
        rf.setNumExecutionSlots(1);
        classifiers.put("rf", rf);

        SGD sgd = new SGD();
        sgd.setLearningRate(this.sgdClassifier.getLearningRate());
        sgd.setEpochs(this.sgdClassifier.getEpochs());
        classifiers.put("sgd", sgd);

        MultilayerPerceptron mlp = new MultilayerPerceptron();
        mlp.setLearningRate(this.multilayerPerceptronClassifier.getLearningRate());
        mlp.setMomentum(this.multilayerPerceptronClassifier.getMomentum());
        mlp.setTrainingTime(this.multilayerPerceptronClassifier.getTrainingTime());
        mlp.setHiddenLayers(this.multilayerPerceptronClassifier.getHiddenLayers());
        classifiers.put("mlp", mlp);

        return classifiers;
    }

    private List<CompletableFuture<ResultsHolder>> trainAndEvaluateClassifiers(
            Map<String, Classifier> classifiers, Instances trainSet, Instances testSet,
            int index, boolean isFeatureSelected, boolean isUnderSampled) {

        return classifiers.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() ->
                        evaluateClassifier(entry.getKey(), entry.getValue(), trainSet, testSet,
                                index, isFeatureSelected, isUnderSampled)))
                .toList();
    }

    private ResultsHolder evaluateClassifier(String classifierType, Classifier classifier,
                                             Instances trainSet, Instances testSet, int index, boolean isFeatureSelected,
                                             boolean isUnderSampled) {

        ResultsHolder defaultResult = new ResultsHolder(index, classifierType,
                isFeatureSelected, isUnderSampled, false);

        try {
            if (trainSet.numInstances() < 2) {
                out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG,
                        classifierType.toUpperCase(), index, ""));
                return defaultResult;
            }

            classifier.buildClassifier(trainSet);
            Evaluation eval = new Evaluation(trainSet);
            eval.evaluateModel(classifier, testSet);

            populateResults(defaultResult, eval, trainSet);
            return defaultResult;

        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG,
                    classifierType.toUpperCase(), index, e.getMessage()));
            return defaultResult;
        }
    }

    private void populateResults(ResultsHolder results, Evaluation eval, Instances trainSet) {
        if (trainSet.classAttribute().numValues() > 1) {
            results.setAuc(eval.areaUnderROC(1));
            results.setPrecision(eval.precision(1));
            results.setRecall(eval.recall(1));
        }
        results.setKappa(eval.kappa());
    }

    private void saveResults(List<ResultsHolder> results, boolean isFeatureSelected,
                             boolean isUnderSampled, boolean isOverSampled) {
        ResultsHolder rf = results.get(0);
        ResultsHolder sgd = results.get(1);
        ResultsHolder mlp = results.get(2);

        List<List<ResultsHolder>> targetLists = determineTargetLists(
                isFeatureSelected, isUnderSampled, isOverSampled);

        targetLists.get(0).add(rf);
        targetLists.get(1).add(sgd);
        targetLists.get(2).add(mlp);
    }

    private List<List<ResultsHolder>> determineTargetLists(boolean isFeatureSelected,
                                                           boolean isUnderSampled, boolean isOverSampled) {

        if (isUnderSampled && isFeatureSelected) {
            return Arrays.asList(featSelUnderSamplRFList, featSelUnderSamplSGDList,
                    featSelUnderSamplNBList);
        } else if (isUnderSampled) {
            return Arrays.asList(underSamplRFList, underSamplSGDList, underSamplNBList);
        } else if (isOverSampled) {
            return Arrays.asList(overSamplRFList, overSamplSGDList, overSamplNBList);
        } else if (isFeatureSelected) {
            return Arrays.asList(featSelRFList, featSelSGDList, featSelNBList);
        } else {
            return Arrays.asList(standardRFList, standardSGDList, standardNBList);
        }
    }
    public void evalCostSensitive(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected)  {
        if (!validateDatasets(trainSet, testSet, index)) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            CostSensitiveClassifier costSensitiveClassifier = createCostSensitiveClassifier();
            Map<String, Classifier> threadLocalClassifiers = createThreadLocalClassifiers();

            List<ResultsHolder> results = evaluateAllClassifiers(
                    costSensitiveClassifier, threadLocalClassifiers, trainSet, testSet, index, isFeatureSelected);

            // Aggiunto false per isUnderSampled e isOverSampled poiché non applicabili per cost-sensitive
            saveResults(results, isFeatureSelected, false, false);

        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "cost-sensitive", index, e.getMessage()));
        }
    }

    private CostSensitiveClassifier createCostSensitiveClassifier() {
        CostSensitiveClassifier classifier = new CostSensitiveClassifier();
        CostMatrix matrix = new CostMatrix(2);
        matrix.setCell(0, 0, 0.0);
        matrix.setCell(1, 1, 0.0);
        matrix.setCell(0, 1, 1.0);
        matrix.setCell(1, 0, 10.0);
        classifier.setCostMatrix(matrix);
        classifier.setMinimizeExpectedCost(true);
        return classifier;
    }
    private List<ResultsHolder> evaluateAllClassifiers(
            CostSensitiveClassifier costSensitiveClassifier,
            Map<String, Classifier> classifiers,
            Instances trainSet,
            Instances testSet,
            int index,
            boolean isFeatureSelected) {

        return classifiers.entrySet().stream()
                .map(entry -> evaluateSingleClassifier(
                        costSensitiveClassifier,
                        entry.getKey(),
                        entry.getValue(),
                        trainSet,
                        testSet,
                        index,
                        isFeatureSelected))
                .toList();
    }
    private ResultsHolder evaluateSingleClassifier(
            CostSensitiveClassifier costSensitiveClassifier,
            String classifierType,
            Classifier baseClassifier,
            Instances trainSet,
            Instances testSet,
            int index,
            boolean isFeatureSelected) {

        try {
            costSensitiveClassifier.setClassifier(baseClassifier);
            costSensitiveClassifier.buildClassifier(trainSet);

            Evaluation eval = new Evaluation(trainSet);
            eval.evaluateModel(costSensitiveClassifier, testSet);

            ResultsHolder results = new ResultsHolder(index, classifierType, isFeatureSelected, false, true);
            populateResults(results, eval, trainSet);
            return results;

        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG,
                    classifierType.toUpperCase() + " cost-sensitive", index, e.getMessage()));
            return new ResultsHolder(index, classifierType, isFeatureSelected, false, true);
        }
    }







    //metodo che addestra i classificatori con under sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalUnderSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, UNDER_SAMPLING));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, UNDER_SAMPLING));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        try {
            SpreadSubsample filter = new SpreadSubsample();
            filter.setInputFormat(trainSet);
            filter.setDistributionSpread(1.0);
            Instances underSampledSet = Filter.useFilter(trainSet, filter);

            // Validate the under-sampled set before proceeding
            if (underSampledSet == null || underSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_UNDERSAMPLING_RESULT_MSG, index));
                return;
            }

            evalStandard(underSampledSet, testSet, index, false, true, false);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "under-sampling", index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che addestra i classificatori con over sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalOverSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate dataset before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, OVERSAMPLING));
            return;
        }

        try {
            Resample filter = new Resample();
            filter.setBiasToUniformClass(1.0);
            filter.setNoReplacement(false);

            int numAllInstances = trainSet.numInstances();
            int classMajorIndex = trainSet.classAttribute().indexOfValue("false");
            int numMajorInstances = 0;

            // Check if "false" value exists in the class attribute
            if (classMajorIndex == -1) {
                // If "false" doesn't exist, assume the first value is the majority class
                out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
                classMajorIndex = 0;
            }

            for (int i = 0; i < numAllInstances; i++) {
                if (trainSet.instance(i).classValue() == classMajorIndex) {
                    numMajorInstances++;
                }
            }

            // Ensure we have a positive sample size to avoid "bound must be positive" errors
            double sampleSize = ((double) numMajorInstances / numAllInstances) * 2 * 100;
            if (sampleSize <= 0) {
                out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG,OVERSAMPLING, index, ". Using default 100%."));
                sampleSize = 100.0; // Default to 100% if calculation results in non-positive value
            }

            filter.setSampleSizePercent(sampleSize);
            filter.setInputFormat(trainSet);
            Instances overSampledSet = Filter.useFilter(trainSet, filter);

            // Validate the oversampled set before proceeding
            if (overSampledSet == null || overSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_OVERSAMPLING_RESULT_MSG, index));
                return;
            }

            evalStandard(overSampledSet, testSet, index, false, false, true);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, OVERSAMPLING, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che addestra i classificatori con feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index,FEATURE_SELECTION));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, FEATURE_SELECTION));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "training", index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        if (testSet.classIndex() < 0 || testSet.classIndex() >= testSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "test", index));
            testSet.setClassIndex(testSet.numAttributes() - 1);
        }

        try {
            AttributeSelection filter = getFilter(trainSet);

            Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
            Instances filteredTestSet = Filter.useFilter(testSet, filter);

            // Validate the filtered sets before proceeding
            if (filteredTrainSet == null || filteredTrainSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, index));
                return;
            }

            if (filteredTestSet == null || filteredTestSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, index));
                return;
            }

            // Ensure filtered datasets have at least one attribute plus class
            if (filteredTrainSet.numAttributes() < 2) {
                out.println(String.format(WARNING_TOO_FEW_ATTRIBUTES_MSG, index));
                return;
            }

            int numAttrFiltered = filteredTrainSet.numAttributes();
            filteredTrainSet.setClassIndex(numAttrFiltered - 1);
            filteredTestSet.setClassIndex(numAttrFiltered - 1);

            evalStandard(filteredTrainSet, filteredTestSet, index, true, false, false);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, FEATURE_SELECTION, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    private AttributeSelection getFilter(Instances trainSet) throws Exception {
        // Use the release index as a key for caching
        // We extract it from the relation name which contains the release number
        String relationName = trainSet.relationName();
        int releaseIndex = -1;

        // Try to extract the release number from the relation name
        if (relationName.contains("_R")) {
            try {
                String releaseStr = relationName.substring(relationName.indexOf("_R") + 2);
                // Check for both "_" and "-" as potential delimiters
                if (releaseStr.contains("_")) {
                    releaseStr = releaseStr.substring(0, releaseStr.indexOf("_"));
                } else if (releaseStr.contains("-")) {
                    releaseStr = releaseStr.substring(0, releaseStr.indexOf("-"));
                }
                releaseIndex = Integer.parseInt(releaseStr);
            } catch (Exception e) {
                // If we can't parse the release number, we'll just create a new filter
                out.println(String.format(RELEASE_NUMBER_WARNING_MSG, relationName));
            }
        }

        // Check if we have a cached filter for this release
        if (releaseIndex != -1 && featureSelectionCache.containsKey(releaseIndex)) {
            out.println(String.format(CACHED_FILTER_MSG, releaseIndex));
            return featureSelectionCache.get(releaseIndex);
        }

        // Create a new filter
        out.println(String.format(NEW_FILTER_MSG, releaseIndex != -1 ? " for release " + releaseIndex : ""));
        CfsSubsetEval eval = new CfsSubsetEval();
        AttributeSelection filter = new AttributeSelection();
        GreedyStepwise searcher = new GreedyStepwise();
        searcher.setSearchBackwards(true);
        filter.setSearch(searcher);
        filter.setEvaluator(eval);
        filter.setInputFormat(trainSet);

        // Apply the filter to get selected attributes
        Instances filteredData = Filter.useFilter(trainSet, filter);

        // Log the selected and discarded features
        logFeatureSelection(trainSet, filteredData, releaseIndex);

        // Cache the filter if we have a valid release index
        if (releaseIndex != -1) {
            featureSelectionCache.put(releaseIndex, filter);
        }

        return filter;
    }

    /**
     * Logs the features that were selected and discarded during feature selection.
     *
     * @param originalData The original dataset before feature selection
     * @param filteredData The dataset after feature selection
     * @param releaseIndex The release index for which feature selection was performed
     */
    private void logFeatureSelection(Instances originalData, Instances filteredData, int releaseIndex) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append(String.format(FEATURE_SELECTION_LOG_MSG, releaseIndex));

        // Get all attributes from original data (excluding class attribute)
        ArrayList<Attribute> originalAttributes = new ArrayList<>();
        for (int i = 0; i < originalData.numAttributes() - 1; i++) {
            originalAttributes.add(originalData.attribute(i));
        }

        // Get all attributes from filtered data (excluding class attribute)
        ArrayList<Attribute> filteredAttributes = new ArrayList<>();
        for (int i = 0; i < filteredData.numAttributes() - 1; i++) {
            filteredAttributes.add(filteredData.attribute(i));
        }

        // Find selected features
        logMessage.append("Selected Features (").append(filteredAttributes.size()).append("):\n");
        for (Attribute attr : filteredAttributes) {
            logMessage.append("  - ").append(attr.name()).append("\n");
        }

        // Find discarded features
        ArrayList<Attribute> discardedAttributes = new ArrayList<>(originalAttributes);
        discardedAttributes.removeAll(filteredAttributes);

        logMessage.append("Discarded Features (").append(discardedAttributes.size()).append("):\n");
        for (Attribute attr : discardedAttributes) {
            logMessage.append("  - ").append(attr.name()).append("\n");
        }

        // Print the log message
        out.println(logMessage.toString());

        // Optionally, write to a file
        try {
            String fileName = "feature_selection_log_release_" + releaseIndex + ".txt";
            try (FileWriter writer = new FileWriter(fileName, true)) {
                writer.write(logMessage.toString());
                writer.write("\n");
            }
            out.println(String.format(FEATURE_LOG_WRITTEN_MSG, fileName));
        } catch (IOException e) {
            out.println(String.format(FEATURE_LOG_ERROR_MSG, e.getMessage()));
        }
    }

    //metodo che addestra i classificatori con sampling e feature selection.
//Effettua un passo del walk forward per i tre classificatori
    public void evalUnderSampFeatureSelection(Instances trainSet, Instances testSet, int index) {
        if (!validateDatasets(trainSet, testSet, index, "under-sampling with feature selection")) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            ProcessedDatasets processedData = applyFeatureSelection(trainSet, testSet, index);
            if (processedData == null) {
                return;
            }

            Instances underSampledSet = applyUnderSampling(processedData.filteredTrainSet, index);
            evalStandard(underSampledSet, processedData.filteredTestSet, index, true, true, false);

        } catch (Exception e) {
            handleEvaluationError("under-sampling with feature selection", index, e);
        }
    }

    private boolean validateDatasets(Instances trainSet, Instances testSet, int index, String evaluationType) {
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, evaluationType));
            return false;
        }
        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, evaluationType));
            return false;
        }
        return true;
    }

    private ProcessedDatasets applyFeatureSelection(Instances trainSet, Instances testSet, int index) {
        try {
            AttributeSelection filter = getFilter(trainSet);
            Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
            Instances filteredTestSet = Filter.useFilter(testSet, filter);

            if (!validateFilteredDatasets(filteredTrainSet, filteredTestSet, index)) {
                return null;
            }

            setClassIndices(filteredTrainSet, filteredTestSet);
            return new ProcessedDatasets(filteredTrainSet, filteredTestSet);

        } catch (Exception e) {
            handleEvaluationError(FEATURE_SELECTION, index, e);
            return null;
        }
    }

    private boolean validateFilteredDatasets(Instances filteredTrain, Instances filteredTest, int index) {
        if (filteredTrain == null || filteredTrain.numInstances() == 0) {
            out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, "empty training dataset", index));
            return false;
        }
        if (filteredTest == null || filteredTest.numInstances() == 0) {
            out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, "empty test dataset", index));
            return false;
        }
        if (filteredTrain.numAttributes() < 2) {
            out.println(String.format(WARNING_TOO_FEW_ATTRIBUTES_MSG, index));
            return false;
        }
        return true;
    }

    private void setClassIndices(Instances filteredTrain, Instances filteredTest) {
        int numAttrFiltered = filteredTrain.numAttributes();
        filteredTrain.setClassIndex(numAttrFiltered - 1);
        filteredTest.setClassIndex(numAttrFiltered - 1);
    }

    private Instances applyUnderSampling(Instances dataset, int index) {
        try {
            SpreadSubsample filterSample = new SpreadSubsample();
            filterSample.setInputFormat(dataset);
            filterSample.setDistributionSpread(1.0);
            Instances underSampledSet = Filter.useFilter(dataset, filterSample);

            if (underSampledSet == null || underSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_UNDERSAMPLING_RESULT_MSG, index));
                return new Instances(dataset);
            }
            return underSampledSet;

        } catch (Exception e) {
            handleEvaluationError("under-sampling", index, e);
            return new Instances(dataset);
        }
    }

    private void handleEvaluationError(String operation, int index, Exception e) {
        out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, operation, index, e.getMessage()));
    }

    private static class ProcessedDatasets {
        final Instances filteredTrainSet;
        final Instances filteredTestSet;

        ProcessedDatasets(Instances filteredTrain, Instances filteredTest) {
            this.filteredTrainSet = filteredTrain;
            this.filteredTestSet = filteredTest;
        }
    }


    //metodo che addestra i classificatori con cost sensitive e feature selection.
//Effettua un passo del walk forward per i tre classificatori
    public void evalCostFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, EXPENSIVE_FEATURE_SELECTION));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, EXPENSIVE_FEATURE_SELECTION));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "training", index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        if (testSet.classIndex() < 0 || testSet.classIndex() >= testSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "test", index));
            testSet.setClassIndex(testSet.numAttributes() - 1);
        }

        try {
            AttributeSelection filter = getFilter(trainSet);

            Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
            Instances filteredTestSet = Filter.useFilter(testSet, filter);

            // Validate the filtered sets before proceeding
            if (filteredTrainSet == null || filteredTrainSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, "empty training dataset", index));
                return;
            }

            if (filteredTestSet == null || filteredTestSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, "empty test dataset", index));
                return;
            }

            // Ensure filtered datasets have at least one attribute plus class
            if (filteredTrainSet.numAttributes() < 2) {
                out.println(String.format(WARNING_TOO_FEW_ATTRIBUTES_MSG, index));
                return;
            }

            int numAttrFiltered = filteredTrainSet.numAttributes();
            filteredTrainSet.setClassIndex(numAttrFiltered - 1);
            filteredTestSet.setClassIndex(numAttrFiltered - 1);

            evalCostSensitive(filteredTrainSet, filteredTestSet, index, true);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, EXPENSIVE_FEATURE_SELECTION, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che prende i risultati e li salva su un csv
    public void csvWriter(List<List<ResultsHolder>> list){
        String path = projectName+"ResultsForJMP.csv";
        try (FileWriter writer = new FileWriter(path)) {

            writer.write("Classifier,feature selection,sampling,cost sensitive,precision,recall,auc,kappa\n");

            for(List<ResultsHolder> miniList:list){
                for(ResultsHolder miniMiniList:miniList){
                    writer.write(miniMiniList.getClassifier()+","+miniMiniList.isFeatureSelection()+","+
                            miniMiniList.isSampling()+","+miniMiniList.isCostSensitive()+","+
                            miniMiniList.getPrecision()+","+miniMiniList.getRecall()+","+
                            miniMiniList.getAuc()+","+miniMiniList.getKappa()+"\n");
                }
            }

            out.println(CSV_SUCCESS_MSG);
        } catch (IOException e) {
            out.println(String.format(CSV_ERROR_MSG, e.getMessage()));
        }
    }

    public ResultsHolder avgCalculator(List<ResultsHolder> list){
        int len = list.size();
        String classifier = list.get(0).getClassifier();
        boolean isFeatureSelected = list.get(0).isFeatureSelection();
        boolean isSampled = list.get(0).isSampling();
        boolean isCostSens = list.get(0).isCostSensitive();
        double precision = 0;
        double recall = 0;
        double auc = 0;
        double kappa = 0;
        for(ResultsHolder r: list){
            precision = precision + r.getPrecision();
            recall = recall + r.getRecall();
            auc = auc + r.getAuc();
            kappa = kappa + r.getKappa();
        }
        precision = precision / len;
        recall = recall / len;
        auc = auc / len;
        kappa = kappa / len;

        ResultsHolder avgResult = new ResultsHolder(-1,classifier,isFeatureSelected,isSampled,isCostSens);
        avgResult.setPrecision(precision);
        avgResult.setRecall(recall);
        avgResult.setAuc(auc);
        avgResult.setKappa(kappa);

        return avgResult;
    }
}