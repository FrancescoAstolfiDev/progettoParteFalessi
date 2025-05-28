package project.controllers;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.*;
import java.util.logging.Logger;

import static java.lang.System.out;

public class MethodDataSetExecutor {

    private static final Logger LOGGER = Logger.getLogger(JiraInfoRetrieve.class.getName());
    private String currentProject;
    private GitHubInfoRetrieve gitHubInfoRetrieve;
    private MetricsCalculator metricsCalculator;
    private List<RevCommit> commits;
    private String basepath="/Users/francescoastolfi/progetto-java/falessi_fra/method_ck_definitive/My_Ispw2_Project_Falessi/csv";
    private List<Release>lastReleaseList;

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
        this.commits=allCommits;

        // Fase 2: Ordinamento e associazione commit-release
        jiraInfoRetrieve.sortReleaseList(releaseList);

        gitHubInfoRetrieve.orderCommitsByReleaseDate(allCommits, releaseList);
        gitHubInfoRetrieve.setReleaseLastCommit(releaseList);

//        // Fase 3: Limitazione all'insieme di release analizzabili (metà del totale)
//        List<Release> analyzableReleases = releaseList.subList(0, releaseList.size() / 2);

        // Fase 4: Recupero dei ticket e associazione commit-ticket
        List<Ticket> allTickets = jiraInfoRetrieve.retrieveTickets(releaseList);
        LOGGER.info("Retrieved " + allTickets.size() + " tickets");
        getAllClassesByRelease(releaseList);


//


        // Fase 6: Estrazione metodi per ogni release<--------DA CApire dove posizionare
        getAllMethodsByRelease(releaseList);
        associateCommitsToTicket(allCommits, allTickets);

        allTickets.removeIf(t -> t.getAssociatedCommits().isEmpty());
        LOGGER.info("Filtered to " + allTickets.size() + " tickets with associated commits");
        if (allTickets.size() < 5) {
            LOGGER.severe("Insufficient tickets with commits (" + allTickets.size() +
                    "). Cannot proceed with analysis.");
            return;
        }

        //double proportion = coldStartProportion();
        double proportion =2.15;
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



        List<Release> halfReleaseList = releaseList.subList(0, releaseList.size() / 2);



        // Inizializzazione della lista lastReleaseList
        lastReleaseList = new ArrayList<>();

        if (releaseList.size() > 0) {
            Release lastRelease = releaseList.get(releaseList.size() - 1);
            lastRelease.setReleaseAllClass(halfReleaseList.get(halfReleaseList.size()-1).getReleaseAllClass());
            lastReleaseList.add(lastRelease);

            // Assegnazione dei metodi dall'ultima release analizzabile
            if (halfReleaseList.size() > 0) {
                Release lastAnalyzableRelease =halfReleaseList.get(halfReleaseList.size() - 1);
                lastRelease.setReleaseAllMethods(lastAnalyzableRelease.getReleaseAllMethods());
            }
        }

        this.metricsCalculator= new MetricsCalculator(this.gitHubInfoRetrieve);
        metricsCalculator.calculateAll(halfReleaseList);


        // Fase 8: Scrittura dei file di training
        for (int i = 1; i < halfReleaseList.size(); i++) {
            Release release= halfReleaseList.get(i);
            writeReleaseTrainFile(release, releaseList);
        }
        writeTestFiles(releaseList, halfReleaseList);



        // Fase 10: Conversione CSV → ARFF
        CSVtoARFFConverter.executeConversion(currentProject, halfReleaseList.size());
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

    private void writeReleaseTrainFile(Release currRelease, List<Release> releaseList) {
        String path = currentProject.toUpperCase() + "_Train_Method_Release_" + currRelease.getName() + ".csv";
        List<Release> incrementalReleaseList = releaseList.subList(0, currRelease.getId());
        List<Ticket> releaseTickets = currRelease.getAllReleaseTicket();

        adjustIvTickets(releaseTickets, currRelease.getCurrentProportion(), releaseList);
        out.println("this is ur path  "+path);
        writeFile(path, incrementalReleaseList, currRelease, releaseTickets);
    }


    public void adjustIvTickets(List<Ticket> tickets, double proportion, List<Release> releaseList){
        for(Ticket ticket:tickets){
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

    private void writeTestFiles(List<Release> releaseList, List<Release> halfReleaseList) {
        int len = halfReleaseList.size();
        List<Ticket> ticketsForTest = releaseList.get(releaseList.size() - 1).getAllReleaseTicket();
        adjustIvTickets(ticketsForTest, releaseList.get(releaseList.size()-1).getCurrentProportion(), releaseList);

        for(int i = 1; i < len; i++) {
            Release currRelease = releaseList.get(i);
            String path = currentProject.toUpperCase() + "_Test_Release_" + currRelease.getName() + ".csv";
            List<Release> incrementalReleaseList = new ArrayList<>();
            incrementalReleaseList.add(currRelease);
            out.println("this is ur path  "+path);
            writeFile(path,incrementalReleaseList,currRelease,ticketsForTest);

        }
    }

    private void writeFile(String path, List<Release> incrementalReleaseList, Release currRelease, List<Ticket> tickets) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Il parametro 'path' non può essere null o vuoto.");
        }
        out.println("release correntemente analizzato " + currRelease.getName());
        try {
            Path outputFilePath = Paths.get(basepath, path);

            if (Files.exists(outputFilePath)) {
                out.println("Il file " + outputFilePath + " esiste già. Computazione saltata.");
            } else {
                if (Files.notExists(outputFilePath.getParent())) {
                    Files.createDirectories(outputFilePath.getParent());
                }

                if (Files.notExists(outputFilePath)) {
                    Files.createFile(outputFilePath);
                }

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath.toFile()))) {
                    // Header
                    writer.write(String.join(",",
                            "release", "class", "method", "path",
                            "loc", "wmc", "assignmentsQty", "mathOperationsQty", "qtyTryCatch", "qtyReturn", "fanin", "fanout",
                            "age","nAuth", "nr", "nSmell","buggy"));
                    writer.newLine();


                    System.out.println("Scorro tutte le release precedenti a quella corrente, calcolo la buggyness e scrivo il file (cur release, incrementalList)= (" + currRelease + "," + incrementalReleaseList + ")");
                    List<Ticket> usableTicket = new ArrayList<>();
                    for (Release release : incrementalReleaseList) {
                        System.out.println("Scorro la release " + release.getName());
                        if (release == currRelease) continue;

                        for (Ticket ticket : tickets) {
                            Release iv = ticket.getCalculatedIv();
                            Release fv = ticket.getFv();

                            // Verifica se IV è null
                            if (iv == null) {
                                //System.out.println("Ticket " + ticket.getKey() + " ha IV null");
                                continue;
                            }



                            // Verifica ciascuna condizione separatamente
                            boolean cond1 = iv.getId() <= release.getId();
                            boolean cond2 = fv.getId() > release.getId();
                            boolean cond3 = iv.getId() < fv.getId();
                            System.out.println("condizioni: " + cond1 + " " + cond2 + " " + cond3);


                            if (cond1 && cond2 && cond3) {
                                usableTicket.add(ticket);

                            }
                        }
                    }

                        System.out.println("Usabile: " + usableTicket.size());


                        System.out.println("Ticket rilevanti trovati per la release " + currRelease.getName() + ": " + usableTicket.size());
                        // Calcola metriche per la release corrente

                        Map<String, MethodInstance> results = this.metricsCalculator.calculateReleaseMetrics(commits, currRelease, usableTicket);


                        for ( MethodInstance   result : results .values()) {



                            String csvRow = String.join(",",
                                    escapeCsv(result.getRelease().getName()),
                                    escapeCsv(result.getClassName()),
                                    escapeCsv(result.getMethodName()),
                                    escapeCsv(String.valueOf(result.getFilePath())),

                                    String.valueOf(result.getLoc()),
                                    String.valueOf(result.getWmc()),
                                    String.valueOf(result.getQtyAssigment()),
                                    String.valueOf(result.getQtyMathOperations()),
                                    String.valueOf(result.getQtyTryCatch()),
                                    String.valueOf(result.getQtyReturn()),
                                    String.valueOf(result.getFanin()),
                                    String.valueOf(result.getFanout()),


                                    String.valueOf(result.getAge()),
                                    String.valueOf(result.getnAuth()),
                                    String.valueOf(result.getNr()),
                                    String.valueOf(result.getnSmells()),
                                    String.valueOf(result.isBuggy())
                            );

                            writer.write(csvRow);
                            writer.newLine();


                    }
                } catch (GitAPIException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {

        }
    }



    // Funzione per escape CSV sicuro
    private String escapeCsv(String field) {
        if (field == null) return "";
        boolean hasSpecial = field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r") ;
        if (hasSpecial) {
            field = field.replace("\"", "\"\""); // raddoppia le virgolette
            return "\"" + field + "\"";
        }
        return field;
    }

    private void sortCommits(List<RevCommit> commits) {
        commits.sort(Comparator.comparing(c -> c.getCommitterIdent().getWhen()));
    }



    private void getAllMethodsByRelease(List<Release> releaseList) throws IOException {
        for (Release release : releaseList) {
            gitHubInfoRetrieve.getMethodInstancesOfCommit(release);
        }
        Release last = releaseList.get(releaseList.size() - 1);
        last.setReleaseAllMethods(releaseList.get(releaseList.size() - 1).getReleaseAllMethods());
    }

    //metodo che data una lista di commits e una di tickets, assegna ad ogni ticket i commit che sono correlati a quel ticket
    //ovvero i commit che citano i ticket nel loro commento
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

    //metodo che setta la lista di file presenti alla release
    private void getAllClassesByRelease(List<Release> releaseList) throws IOException {
        int len = releaseList.size();
        for (int i = 0; i < len; i++) {
            gitHubInfoRetrieve.getClassFilesOfCommit(releaseList.get(i));
        }
        releaseList.get(releaseList.size()-1).setReleaseAllClass(releaseList.get(len-1).getReleaseAllClass());
    }

    //metodo per il calcolo del proportion in caso non abbia sufficienti ticket
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

    //semplice metodo che applica la formula del proportion vista a lezione
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

