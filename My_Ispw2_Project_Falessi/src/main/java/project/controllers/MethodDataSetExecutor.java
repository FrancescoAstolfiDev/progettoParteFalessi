package project.controllers;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import project.models.DataSetType;
import project.models.Release;
import project.models.Ticket;
import project.utils.ConstantSize;
import project.utils.ConstantsWindowsFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.*;
import java.lang.Thread;

import static java.lang.System.out;

public class MethodDataSetExecutor {
    private final Logger LOGGER = LoggerFactory.getLogger(MethodDataSetExecutor.class);
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
        // Fase 1: Recupero delle release e dei commit
        JiraInfoRetrieve jiraInfoRetrieve = new JiraInfoRetrieve(this.currentProject);


        List<Release> releaseList = jiraInfoRetrieve.retrieveReleases();
        LOGGER.info("Retrieved " + releaseList.size() + " releases");

        List<RevCommit> allCommits = gitHubInfoRetrieve.getAllCommits();
        LOGGER.info("Retrieved " + allCommits.size() + " commits");


        // Fase 2: Ordinamento e associazione commit-release
        jiraInfoRetrieve.sortReleaseList(releaseList);

        gitHubInfoRetrieve.orderCommitsByReleaseDate(allCommits, releaseList);
        gitHubInfoRetrieve.setReleaseLastCommit(releaseList);

        // Fase 3: Limitazione all'insieme di release analizzabili (metà del totale)
        //List<Release> analyzableReleases = releaseList.subList(0, releaseList.size() / 2);

        // Fase 4: Recupero dei ticket e associazione commit-ticket
        List<Ticket> allTickets = jiraInfoRetrieve.retrieveTickets(releaseList);
        LOGGER.info("Retrieved {} tockets" , allTickets.size());

        getAllClassesByRelease(releaseList);
        LOGGER.info("Retrieved {} classes from all the release " , releaseList.get(releaseList.size()-1).getReleaseAllClass().size() );


        LOGGER.info("Retrieved {} methods from all the class " , releaseList.get(releaseList.size()-1).getReleaseAllMethods().size() );
        associateCommitsToTicket(allCommits, allTickets);

        allTickets.removeIf(t -> t.getAssociatedCommits().isEmpty());
        LOGGER.info("Filtered to {}  tickets with associated commits" , allTickets.size());

        if (allTickets.size() < 5) {
            LOGGER.error("Insufficient tickets with commits ({}) Cannot proceed with analysis. " , allTickets.size());
            return;
        }

        double proportion = coldStartProportion();
//        double proportion =2.15;
        jiraInfoRetrieve.assignTicketToRelease(releaseList,allTickets);

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
                List<Ticket> ticketsWithAv = getTicketsWithAv(release.getAllReleaseTicket());

                if (ticketsWithAv.size() < 5){
                    release.setCurrentProportion(proportion);
                }
                else {
                    release.setCurrentProportion(calculateProportion(ticketsWithAv));
                }
            }
        }

        int split=Math.max(1, (int) (releaseList.size() * ConstantSize.SPLIT_PERCENTAGE));

        List<Release> avaiableTrainingRelease = releaseList.subList(0, split);

        // Initialize the metrics calculator with only the needed commits and the current project name
        this.metricsCalculator = new MetricsCalculator(this.gitHubInfoRetrieve, this.currentProject);
        metricsCalculator.calculateAll(avaiableTrainingRelease);

        // Fase 8: Scrittura dei file di training
        for (int i = 1; i < avaiableTrainingRelease.size()-1; i++) {
            // reverse calculation for have first all the commit processed and elaborated
            Release release= avaiableTrainingRelease.get(i);
            writeReleaseFile(release, releaseList, DataSetType.TRAINING, false);
        }
        for (int i=2 ; i< avaiableTrainingRelease.size();i++){
            Release release=avaiableTrainingRelease.get(i);
            writeReleaseFile(release, releaseList, DataSetType.TEST,false);
            if(i==avaiableTrainingRelease.size()-1){
               writeReleaseFile(release, releaseList, DataSetType.TEST,true);
            }
        }

        CSVtoARFFConverter.executeConversion(currentProject,avaiableTrainingRelease.size());

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



    public List<Ticket> getAddTicket(List<Release> releaseList){
        List<Ticket> releaseTickets;
        List<Ticket> allTickets = new ArrayList<>();
        for(int i=0; i<releaseList.size(); i++){
            Release release = releaseList.get(i);
            releaseTickets = new ArrayList<>(release.getAllReleaseTicket());
            allTickets.addAll(releaseTickets);
        }
        adjustIvTickets(allTickets, releaseList.get(releaseList.size()-1).getCurrentProportion(), releaseList);
        return allTickets;
    }



    private void writeReleaseFile(Release curRelease, List<Release> releaseList, DataSetType datasetTipe, boolean isLastRelease) {
        this.currentProcessingRelease = curRelease;
        List<Ticket> tickets= new ArrayList<>();
        for(int i=0;i<curRelease.getId();i++){
            Release release=releaseList.get(i);
            tickets.addAll(release.getAllReleaseTicket());
        }
        adjustIvTickets(tickets, curRelease.getCurrentProportion(), releaseList);
        if(isLastRelease){
            tickets.addAll(getAddTicket(releaseList));
        }
        writeFile(releaseList.subList(0,curRelease.getId()), curRelease, tickets, datasetTipe);
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



    private void writeFile(List<Release> incrementalReleaseList, Release currRelease, List<Ticket> tickets , DataSetType dataSetType) {
        // I need to discard the calculation if I already find completed files
        System.out.println("currently analyzing release " + currRelease.getName());
        String outPath = currentProject.toUpperCase() + dataSetType + currentProcessingRelease.getId() + ".csv";
        Path outputFilePath=null;
        if(dataSetType==DataSetType.TRAINING){
            outputFilePath = ConstantsWindowsFormat.CSV_PATH.resolve(outPath);
        } if (dataSetType==DataSetType.TEST){
            outputFilePath = ConstantsWindowsFormat.TEST_CSV_PATH.resolve(outPath);
        }

        if ( Files.exists(outputFilePath) ) {
            try {
                // Check if the file is valid (not empty)
                long fileSize = Files.size(outputFilePath);
                if (fileSize > 0) {
                    System.out.println("The file " + outputFilePath + " already exists and is valid. Computation skipped.");
                    return;
                } else {
                    System.out.println("The file " + outputFilePath + " exists but is empty. Proceeding with computation.");
                    // Delete the empty file
                    Files.delete(outputFilePath);
                }
            } catch (IOException e) {
                System.out.println("Error checking file " + outputFilePath + ": " + e.getMessage() + ". Proceeding with computation.");
                // If there's an error checking the file, proceed with the computation
            }
        }
        // filtering from all the tickets avaiable the tickets that a release could use

       List<Ticket> usableTicket = new ArrayList<>();
        for (Release release : incrementalReleaseList) {
            System.out.println("Iterating through release " + release.getName());
            if (release == currRelease) continue;
            for (Ticket ticket : tickets) {
                if (ticket.getCalculatedIv().getId() <= release.getId()
                        && ticket.getCalculatedIv().getId() < ticket.getFv().getId()) {
                    usableTicket.add(ticket);
                }
            }
        }

        System.out.println("Usable: " + usableTicket.size());
        System.out.println("Relevant tickets found for release " + currRelease.getName() + ": " + usableTicket.size());
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
        out.println("\n********************END ASSOCIATION********************");
    }

    //method that sets the list of files present in the release
    private void getAllClassesByRelease(List<Release> releaseList) throws IOException {
        int len = releaseList.size();
        for (int i = 0; i < len; i++) {
            gitHubInfoRetrieve.getClassFilesOfCommit(releaseList.get(i));
        }
        releaseList.get(releaseList.size()-1).setReleaseAllClass(releaseList.get(len-1).getReleaseAllClass());
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
