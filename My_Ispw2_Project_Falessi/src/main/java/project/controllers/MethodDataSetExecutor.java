package project.controllers;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import project.utils.Projects;
import project.models.DataSetType;
import project.models.Release;
import project.models.Ticket;
import project.statefull.ConstantsWindowsFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.*;




public class MethodDataSetExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodDataSetExecutor.class);
    private String currentProject;
    private GitHubInfoRetrieve gitHubInfoRetrieve;
    private MetricsCalculator metricsCalculator;
    private Release currentProcessingRelease;






    public MethodDataSetExecutor(String name) throws IOException {
        this.currentProject = name;
        gitHubInfoRetrieve = new GitHubInfoRetrieve(this.currentProject);
    }
    private List<String> projects = new ArrayList<>(Arrays.asList("AVRO","OPENJPA","STORM","ZOOKEEPER","BOOKKEEPER","TAJO"));

    public void executeFlow() throws IOException, ParseException, GitAPIException {

        JiraInfoRetrieve jiraInfoRetrieve = new JiraInfoRetrieve(this.currentProject);
        List<Release> releaseList = jiraInfoRetrieve.retrieveReleases();
        LOGGER.info("Retrieved {} release",  releaseList.size());

        List<RevCommit> allCommits = gitHubInfoRetrieve.getAllCommits();
        LOGGER.info("Retrieved {} commits ", allCommits.size() );

        jiraInfoRetrieve.sortReleaseList(releaseList);

        gitHubInfoRetrieve.orderCommitsByReleaseDate(allCommits, releaseList);
        gitHubInfoRetrieve.setReleaseLastCommit(releaseList);


        List<Ticket> allTickets = jiraInfoRetrieve.retrieveTickets(releaseList);
        LOGGER.info("Retrieved {} tickets" , allTickets.size());



        associateCommitsToTicket(allCommits, allTickets);
        allTickets.removeIf(t -> t.getAssociatedCommits().isEmpty());
        LOGGER.info("Filtered to {}  tickets with associated commits" , allTickets.size());

        if (allTickets.size() < 5) {
            LOGGER.error("Insufficient tickets with commits ({}) Cannot proceed with analysis. " , allTickets.size());
            return;
        }

        double proportion = coldStartProportion();// 2.15
        //se non ho sufficienti ticket in tutto il progetto posso settare il proportion di tutte le release al valore
        //ottenuto tramite cold start
        if (jiraInfoRetrieve.getTicketsWithValidAV().size() < 5) {
            for (Release release:releaseList){
                release.setCurrentProportion(proportion);
            }
        }
        //scorro tutte le release e assegno i vari valori di proportion
        else {
            for (Release release:releaseList){
                List<Ticket> ticketsWithAv = getTicketsWithAv(JiraInfoRetrieve.getAllReleaseTicket(release,allTickets));
                if (ticketsWithAv.size() < 5){
                    release.setCurrentProportion(proportion);
                }
                else {
                    release.setCurrentProportion(calculateProportion(ticketsWithAv));
                }
            }
        }

        Projects curProject = Projects.fromString(currentProject);
        int split=Math.max(1, (int) (releaseList.size() * curProject.getSplit()));
        List<Release> avaiableTrainingRelease = releaseList.subList(0, split);

        curProject.setRefactoredClass(releaseList);
        getAllClassesByRelease(avaiableTrainingRelease);
        LOGGER.info("Retrieved {} classes from all the release " , avaiableTrainingRelease.get(avaiableTrainingRelease.size()-1).getReleaseAllClass().size() );

        // Initialize the metrics calculator with only the needed commits and the current project name
        this.metricsCalculator = new MetricsCalculator(this.gitHubInfoRetrieve, this.currentProject , curProject);
        metricsCalculator.calculateAll(avaiableTrainingRelease);

        for (int i = 1; i < avaiableTrainingRelease.size()-1; i++) {
            // reverse calculation for have first all the commit processed and elaborated
            Release release= avaiableTrainingRelease.get(i);
            writeReleaseFile(release, releaseList, DataSetType.TRAINING, false,allTickets);
        }
        for (int i=2 ; i< avaiableTrainingRelease.size();i++){
            Release release=avaiableTrainingRelease.get(i);
            writeReleaseFile(release, releaseList, DataSetType.TEST,false,allTickets);
            if(i==avaiableTrainingRelease.size()-1){
               writeReleaseFile(release, releaseList, DataSetType.TEST,true,allTickets);
            }
        }
        CSVtoARFFConverter.executeConversion(currentProject,avaiableTrainingRelease.size());

    }

    //method that sets the list of files present in the release
    private void getAllClassesByRelease(List<Release> releaseList) throws IOException {
        int len = releaseList.size();
        for (int i = 0; i < len; i++) {
            gitHubInfoRetrieve.getClassFilesOfCommit(releaseList.get(i));
        }
    }
    private List<Ticket> getTicketsWithAv(List<Ticket> allTicket) {
        List<Ticket> goodTickets = new ArrayList<>();
        for (Ticket t : allTicket) {
            if (t.getIv() != null) {
                goodTickets.add(t);
            }
        }
        return goodTickets;
    }



    public List<Ticket> getAddTicket(List<Release> releaseList,List<Ticket> allTickets){
        List<Ticket> addTickets ;
        addTickets = new ArrayList<>(JiraInfoRetrieve.getAllReleaseTicket(releaseList.get(releaseList.size()-1),allTickets));
        adjustIvTickets(addTickets, releaseList.get(releaseList.size()-1).getCurrentProportion(), releaseList);
        return addTickets;
    }



    private void writeReleaseFile(Release curRelease, List<Release> releaseList, DataSetType datasetTipe, boolean isLastRelease , List<Ticket> allTickets) {
        this.currentProcessingRelease = curRelease;
        List<Ticket> tickets = new ArrayList<>(JiraInfoRetrieve.getAllReleaseTicket(curRelease, allTickets));
        adjustIvTickets(tickets, curRelease.getCurrentProportion(), releaseList);
        if(isLastRelease){
            tickets.addAll(getAddTicket(releaseList,allTickets));
        }
        try {
            writeFile( curRelease, tickets, datasetTipe);
        } catch (IOException e) {
            LOGGER.error("Error writing release file: {}" , e.getMessage());
        }
    }


    public void adjustIvTickets(List<Ticket> tickets, double proportion, List<Release> releaseList){
        for(Ticket ticket:tickets){
            if(ticket.getIv() == null && ticket.getOv().getId() > releaseList.size()-1){
                ticket.setIv(releaseList.get(releaseList.size()-1));
            }
            if(ticket.getIv() == null){
                int ov = ticket.getOv().getId();
                int fv = ticket.getFv().getId();
                int iv;

                if(fv == ov){
                    iv = (int) (fv -(proportion * 1));
                }
                else{
                    iv = (int) (fv - (proportion * (fv - ov)));
                }

                if(iv <= 0){
                    iv = 1;
                }
                ticket.setCalculatedIv(releaseList.get(iv));
            }
            else{
                ticket.setCalculatedIv(ticket.getIv());
            }
        }
    }



    private void writeFile(Release currRelease, List<Ticket> tickets , DataSetType dataSetType) throws IOException {
        // I need to discard the calculation if I already find completed files
        LOGGER.info("\n\ncurrently analyzing release {} " , currRelease.getName());
        String outPath = currentProject.toUpperCase() + dataSetType + currentProcessingRelease.getId() + ".csv";
        Path outputFilePath= dataSetType==DataSetType.TEST?ConstantsWindowsFormat.TEST_CSV_PATH.resolve(outPath):ConstantsWindowsFormat.CSV_PATH.resolve(outPath);
        if ( Files.exists(outputFilePath) ) {
            // Check if the file is valid (not empty)
            long fileSize = Files.size(outputFilePath);
            if (fileSize > 0) {
               LOGGER.info("The file {} already exists and is valid. Computation skipped."  ,outputFilePath );
                return;
            } else {
                LOGGER.info("The file {} exists but is empty. Proceeding with computation.  " , outputFilePath );
                // Delete the empty file
                Files.delete(outputFilePath);
            }

        }
        // filtering from all the tickets avaiable the tickets that a release could use

       List<Ticket> usableTicket = new ArrayList<>();
        Release injectVersion;
        for (Ticket ticket : tickets) {
            injectVersion=ticket.getIv()==null?ticket.getCalculatedIv():ticket.getIv();
            // 1. il bug esiste prima della fine della release
            // 2. il bug viene iniettato e poi viene aperto
            // 3. il bug viene prima iniettato e poi fixato
            // 4. il bug viene fixato prima della fine della release[ se no non so che farmene per la buggyness]
            // 5. per ottimizzare il processo e diminuire i ticket iv! fv!= iv non ha influenza ai fini della chiusura della release
            if (injectVersion.getId() <= currRelease.getId() &&
                    injectVersion.getId() <= ticket.getOv().getId() &&
                    ticket.getFv().getId() <= currRelease.getId() &&
                    injectVersion.getId() < ticket.getFv().getId()

            ) {
                usableTicket.add(ticket);
            }

        }

        LOGGER.info ("Usable: {}" , usableTicket.size());
        LOGGER.info("Relevant tickets found for release {} : {}",currRelease.getName(), usableTicket.size());
        // Calculate metrics for the current release
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.metricsCalculator.calculateReleaseMetrics( currRelease, usableTicket , dataSetType);

    }


    private void associateCommitsToTicket(List<RevCommit> allCommits, List<Ticket> allTickets) {
        LOGGER.info("\n\n********************BEGIN METHOD-LEVEL COMMIT ASSOCIATION********************");

        for (RevCommit commit : allCommits) {
            String comment = commit.getFullMessage();
            for (Ticket ticket : allTickets) {

                if ((comment.contains(ticket.getKey() + ":") || comment.contains(ticket.getKey() + "]")
                        || comment.contains(ticket.getKey() + " "))
                        && !ticket.getAssociatedCommits().contains(commit)) {

                    ticket.addAssociatedCommit(commit);

                }
            }
        }
        LOGGER.info("\n********************END ASSOCIATION********************");
    }



    //method for calculating the proportion in case there are not enough tickets
    private double coldStartProportion() throws IOException, ParseException {

        projects.remove(this.currentProject.toUpperCase());

        List<Double> proportionList = new ArrayList<>();

        for(String name: projects){
            JiraInfoRetrieve jiraRetrieveTemp = new JiraInfoRetrieve(name);
            List<Release> releaseListTemp = jiraRetrieveTemp.retrieveReleases();
            jiraRetrieveTemp.sortReleaseList(releaseListTemp);
            jiraRetrieveTemp.retrieveTickets(releaseListTemp);
            List<Ticket> ticketColdStart = jiraRetrieveTemp.getTicketsWithValidAV();

            double prop =0.0;
            if(ticketColdStart.size() >= 5) {
                prop = calculateProportion(ticketColdStart);
            }
            proportionList.add(prop);
        }

        Collections.sort(proportionList);


        return proportionList.get(proportionList.size()/2);
    }

    //simple method that applies the proportion formula seen in class
    public double calculateProportion(List<Ticket> tickets){
        double prop = 0.0;
        for(Ticket ticket:tickets){
            int iv = ticket.getIv().getId();
            int fv = ticket.getFv().getId();
            int ov = ticket.getOv().getId();
            prop = prop + (double)(fv-iv) / (fv-ov);
        }
        prop = prop / tickets.size();
        return prop;
    }



}
