package project.controllers;

import project.models.ResultsHolder;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.CostMatrix;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SpreadSubsample;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.System.*;

public class EvaluationFlow {

    RandomForest randomForestClassifier;
    NaiveBayes naiveBayesClassifier;
    IBk ibkClassifier;
    String projectName;
    List<ResultsHolder> standardRFList;
    List<ResultsHolder> standardNBList;
    List<ResultsHolder> standardIBKList;
    List<ResultsHolder> costSensitiveRFList;
    List<ResultsHolder> costSensitiveIBKList;
    List<ResultsHolder> costSensitiveNBList;
    List<ResultsHolder> underSamplRFList;
    List<ResultsHolder> underSamplIBKList;
    List<ResultsHolder> underSamplNBList;
    List<ResultsHolder> overSamplRFList;
    List<ResultsHolder> overSamplIBKList;
    List<ResultsHolder> overSamplNBList;
    List<ResultsHolder> featSelRFList;
    List<ResultsHolder> featSelIBKList;
    List<ResultsHolder> featSelNBList;
    List<ResultsHolder> featSelUnderSamplRFList;
    List<ResultsHolder> featSelUnderSamplIBKList;
    List<ResultsHolder> featSelUnderSamplNBList;
    List<ResultsHolder> featSelCostSensRFList;
    List<ResultsHolder> featSelCostSensIBKList;
    List<ResultsHolder> featSelCostSensNBList;

    public EvaluationFlow(String name){
        this.projectName = name;
        //questi sono i classificatori che utilizzo
        this.randomForestClassifier = new RandomForest();
        this.naiveBayesClassifier = new NaiveBayes();
        this.ibkClassifier = new IBk();

        //queste sono le liste che contengono i risultati delle valutazioni per tipologia di classificatore
        this.standardRFList= new ArrayList<>();
        this.standardNBList= new ArrayList<>();
        this.standardIBKList = new ArrayList<>();
        this.costSensitiveRFList = new ArrayList<>();
        this.costSensitiveIBKList = new ArrayList<>();
        this.costSensitiveNBList = new ArrayList<>();
        this.underSamplRFList = new ArrayList<>();
        this.underSamplIBKList = new ArrayList<>();
        this.underSamplNBList = new ArrayList<>();
        this.overSamplRFList = new ArrayList<>();
        this.overSamplIBKList = new ArrayList<>();
        this.overSamplNBList = new ArrayList<>();
        this.featSelIBKList = new ArrayList<>();
        this.featSelNBList = new ArrayList<>();
        this.featSelRFList = new ArrayList<>();
        this.featSelUnderSamplIBKList = new ArrayList<>();
        this.featSelUnderSamplNBList = new ArrayList<>();
        this.featSelUnderSamplRFList = new ArrayList<>();
        this.featSelCostSensIBKList = new ArrayList<>();
        this.featSelCostSensNBList = new ArrayList<>();
        this.featSelCostSensRFList = new ArrayList<>();
    }

    public void executeFlow() throws Exception {

        int numRelease;
        if(Objects.equals(this.projectName, "bookkeeper")){
            numRelease = 4;
        }
        else{
            numRelease = 17;
        }

        for (int i = 3; i <= numRelease; i++) {

            //recupero i dati dai file .arff
            DataSource trainSource = new DataSource( this.projectName+ "_Train_R" + i + ".arff");
            DataSource testSource = new DataSource(this.projectName + "_Test_R" + i + ".arff");
            Instances trainSet = trainSource.getDataSet();
            Instances testSet = testSource.getDataSet();

            //setto il parametro buggy come variabile di interesse
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
            testSet.setClassIndex(testSet.numAttributes() - 1);

            evalStandard(trainSet,testSet,i,false,false,false);
            evalCostSensitive(trainSet,testSet,i,false);
            evalUnderSampling(trainSet,testSet,i);
            evalOverSampling(trainSet,testSet,i);
            evalFeatureSelection(trainSet,testSet,i);
            evalUnderSampFeatureSelection(trainSet,testSet,i);
            evalCostFeatureSelection(trainSet,testSet,i);
        }

        List<List<ResultsHolder>> allResults = new ArrayList<>();
        allResults.add(standardRFList);
        allResults.add(standardNBList);
        allResults.add(standardIBKList);
        allResults.add(costSensitiveRFList);
        allResults.add(costSensitiveIBKList);
        allResults.add(costSensitiveNBList);
        allResults.add(underSamplRFList);
        allResults.add(underSamplIBKList);
        allResults.add(underSamplNBList);
        allResults.add(overSamplRFList);
        allResults.add(overSamplIBKList);
        allResults.add(overSamplNBList);
        allResults.add(featSelRFList);
        allResults.add(featSelIBKList);
        allResults.add(featSelNBList);
        allResults.add(featSelUnderSamplRFList);
        allResults.add(featSelUnderSamplIBKList);
        allResults.add(featSelUnderSamplNBList);
        allResults.add(featSelCostSensRFList);
        allResults.add(featSelCostSensIBKList);
        allResults.add(featSelCostSensNBList);

        csvWriter(allResults);

    }

    //metodo che addestra i classificatori in maniera standard, ovvero senza sampling, feature selection o
    //cost sensitive. Effettua un passo del walk forward per i tre classificatori
    public void evalStandard(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected, boolean isUnderSampled, boolean isOverSampled) throws Exception {
        //addestramento modelli standard
        this.randomForestClassifier.buildClassifier(trainSet);
        this.ibkClassifier.buildClassifier(trainSet);
        this.naiveBayesClassifier.buildClassifier(trainSet);

        weka.classifiers.Evaluation evalRF = new weka.classifiers.Evaluation(trainSet);
        evalRF.evaluateModel(this.randomForestClassifier, testSet);
        ResultsHolder randomForestEval = new ResultsHolder(index,"rf",isFeatureSelected,isUnderSampled,false);
        randomForestEval.setAuc(evalRF.areaUnderROC(1));
        randomForestEval.setKappa(evalRF.kappa());
        randomForestEval.setPrecision(evalRF.precision(1));
        randomForestEval.setRecall(evalRF.recall(1));


        weka.classifiers.Evaluation evalIBK = new Evaluation(trainSet);
        evalIBK.evaluateModel(this.ibkClassifier, testSet);
        ResultsHolder ibkEval = new ResultsHolder(index,"ibk",isFeatureSelected,isUnderSampled,false);
        ibkEval.setAuc(evalIBK.areaUnderROC(1));
        ibkEval.setKappa(evalIBK.kappa());
        ibkEval.setPrecision(evalIBK.precision(1));
        ibkEval.setRecall(evalIBK.recall(1));


        weka.classifiers.Evaluation evalNB = new weka.classifiers.Evaluation(trainSet);
        evalNB.evaluateModel(this.naiveBayesClassifier, testSet);
        ResultsHolder naiveBayesEval = new ResultsHolder(index,"nb",isFeatureSelected,isUnderSampled,false);
        naiveBayesEval.setAuc(evalNB.areaUnderROC(1));
        naiveBayesEval.setKappa(evalNB.kappa());
        naiveBayesEval.setPrecision(evalNB.precision(1));
        naiveBayesEval.setRecall(evalNB.recall(1));

        if(isUnderSampled && isFeatureSelected){
            this.featSelUnderSamplRFList.add(randomForestEval);
            this.featSelUnderSamplIBKList.add(ibkEval);
            this.featSelUnderSamplNBList.add(naiveBayesEval);
        }
        else if(isUnderSampled){
            this.underSamplRFList.add(randomForestEval);
            this.underSamplIBKList.add(ibkEval);
            this.underSamplNBList.add(naiveBayesEval);
        }
        else if(isOverSampled){
            this.overSamplRFList.add(randomForestEval);
            this.overSamplIBKList.add(ibkEval);
            this.overSamplNBList.add(naiveBayesEval);
        }
        else if(isFeatureSelected){
            this.featSelRFList.add(randomForestEval);
            this.featSelIBKList.add(ibkEval);
            this.featSelNBList.add(naiveBayesEval);
        }
        else{
            this.standardRFList.add(randomForestEval);
            this.standardIBKList.add(ibkEval);
            this.standardNBList.add(naiveBayesEval);
        }

    }

    //metodo che addestra i classificatori con cost sensitive.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalCostSensitive(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected) throws Exception {
        //classifier cost sensitive a cui aggiungo la matrice dei costi
        CostSensitiveClassifier costSensitiveClassifier = new CostSensitiveClassifier();
        CostMatrix matrix = new CostMatrix(2);
        matrix.setCell(0,0,0.0);
        matrix.setCell(1,1,0.0);
        matrix.setCell(0,1,1.0);
        matrix.setCell(1,0,10.0);
        costSensitiveClassifier.setCostMatrix(matrix);
        costSensitiveClassifier.setMinimizeExpectedCost(true);

        //random forest cost sensitive
        costSensitiveClassifier.setClassifier(this.randomForestClassifier);
        costSensitiveClassifier.buildClassifier(trainSet);
        Evaluation evalRF = new Evaluation(trainSet);
        evalRF.evaluateModel(costSensitiveClassifier, testSet);
        ResultsHolder randomForestEval = new ResultsHolder(index,"rf",isFeatureSelected,false,true);
        randomForestEval.setAuc(evalRF.areaUnderROC(1));
        randomForestEval.setKappa(evalRF.kappa());
        randomForestEval.setPrecision(evalRF.precision(1));
        randomForestEval.setRecall(evalRF.recall(1));


        //ibk cost sensitive
        costSensitiveClassifier.setClassifier(this.ibkClassifier);
        costSensitiveClassifier.buildClassifier(trainSet);
        Evaluation evalIBK = new Evaluation(trainSet);
        evalIBK.evaluateModel(costSensitiveClassifier, testSet);
        ResultsHolder ibkResults = new ResultsHolder(index,"ibk",isFeatureSelected,false,true);
        ibkResults.setAuc(evalIBK.areaUnderROC(1));
        ibkResults.setKappa(evalIBK.kappa());
        ibkResults.setPrecision(evalIBK.precision(1));
        ibkResults.setRecall(evalIBK.recall(1));


        //naive bayes cost sensitive
        costSensitiveClassifier.setClassifier(this.naiveBayesClassifier);
        costSensitiveClassifier.buildClassifier(trainSet);
        Evaluation evalNB = new Evaluation(trainSet);
        evalNB.evaluateModel(costSensitiveClassifier, testSet);
        ResultsHolder naiveBayesResults = new ResultsHolder(index,"nb",isFeatureSelected,false,true);
        naiveBayesResults.setAuc(evalNB.areaUnderROC(1));
        naiveBayesResults.setKappa(evalNB.kappa());
        naiveBayesResults.setPrecision(evalNB.precision(1));
        naiveBayesResults.setRecall(evalNB.recall(1));

        if(isFeatureSelected) {
            this.featSelCostSensRFList.add(randomForestEval);
            this.featSelCostSensIBKList.add(ibkResults);
            this.featSelCostSensNBList.add(naiveBayesResults);
        }else{
            this.costSensitiveRFList.add(randomForestEval);
            this.costSensitiveIBKList.add(ibkResults);
            this.costSensitiveNBList.add(naiveBayesResults);
        }
    }

    //metodo che addestra i classificatori con under sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalUnderSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        SpreadSubsample filter = new SpreadSubsample();
        filter.setInputFormat(trainSet);
        filter.setDistributionSpread(1.0);
        Instances underSampledSet = Filter.useFilter(trainSet, filter);

        evalStandard(underSampledSet,testSet,index,false,true,false);

    }

    //metodo che addestra i classificatori con over sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalOverSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        Resample filter = new Resample();
        filter.setBiasToUniformClass(1.0);
        filter.setNoReplacement(false);

        int numAllInstances = trainSet.numInstances();
        int classMajorIndex = trainSet.classAttribute().indexOfValue("false");
        int numMajorInstances = 0;

        for (int i = 0; i < numAllInstances; i++) {
            if (trainSet.instance(i).classValue() == classMajorIndex) {
                numMajorInstances++;
            }
        }

        double sampleSize = ((double) numMajorInstances / numAllInstances) * 2 * 100;
        filter.setSampleSizePercent(sampleSize);
        filter.setInputFormat(trainSet);
        Instances overSampledSet = Filter.useFilter(trainSet, filter);

        evalStandard(overSampledSet,testSet,index,false,false,true);

    }

    //metodo che addestra i classificatori con feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        AttributeSelection filter = getFilter(trainSet);

        Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
        Instances filteredTestSet = Filter.useFilter(testSet, filter);

        int numAttrFiltered = filteredTrainSet.numAttributes();
        filteredTrainSet.setClassIndex(numAttrFiltered - 1);

        evalStandard(filteredTrainSet,filteredTestSet,index,true,false,false);
    }

    private AttributeSelection getFilter(Instances trainSet) throws Exception {
        CfsSubsetEval eval = new CfsSubsetEval();
        AttributeSelection filter = new AttributeSelection();
        GreedyStepwise searcher = new GreedyStepwise();
        searcher.setSearchBackwards(true);
        filter.setSearch(searcher);
        filter.setEvaluator(eval);
        filter.setInputFormat(trainSet);
        return filter;
    }

    //metodo che addestra i classificatori con sampling e feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalUnderSampFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        AttributeSelection filter = getFilter(trainSet);

        Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
        Instances filteredTestSet = Filter.useFilter(testSet, filter);

        int numAttrFiltered = filteredTrainSet.numAttributes();
        filteredTrainSet.setClassIndex(numAttrFiltered - 1);

        SpreadSubsample filterSample = new SpreadSubsample();
        filterSample.setInputFormat(filteredTrainSet);
        filterSample.setDistributionSpread(1.0);
        Instances underSampledSet = Filter.useFilter(filteredTrainSet, filterSample);
        evalStandard(underSampledSet,filteredTestSet,index,true,true,false);
    }

    //metodo che addestra i classificatori con cost sensitive e feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalCostFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        AttributeSelection filter = getFilter(trainSet);

        Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
        Instances filteredTestSet = Filter.useFilter(testSet, filter);

        int numAttrFiltered = filteredTrainSet.numAttributes();
        filteredTrainSet.setClassIndex(numAttrFiltered - 1);

        evalCostSensitive(filteredTrainSet,filteredTestSet,index,true);
    }

    //metodo che prende i  risultati e li salva su un csv
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

        out.println("File CSV creato con successo.");
        } catch (IOException e) {
            out.println("Si è verificato un errore durante la creazione del file CSV: " + e.getMessage());
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
