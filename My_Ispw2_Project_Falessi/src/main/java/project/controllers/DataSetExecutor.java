package project.controllers;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import project.models.ClassFile;
import project.models.Release;
import project.models.Ticket;

import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;

import static java.lang.System.*;

public class DataSetExecutor {

    private List<String> projects = new ArrayList<>(Arrays.asList("AVRO","OPENJPA","STORM","ZOOKEEPER","BOOKKEEPER","TAJO"));
    private String currentProject;

    private GitHubInfoRetrieve gitHubInfoRetrieve;

    public DataSetExecutor(String name) throws IOException {
        this.currentProject = name;
        gitHubInfoRetrieve = new GitHubInfoRetrieve(this.currentProject);
    }
    public void executeFlow() throws IOException, ParseException, GitAPIException {

        JiraInfoRetrieve jiraInfoRetrieve = new JiraInfoRetrieve(this.currentProject);
        //get all the releases in Jira
        List<Release> releaseList = jiraInfoRetrieve.retrieveReleases();

        //get all commits of the project
        List<RevCommit> allCommits = gitHubInfoRetrieve.getAllCommits();

        //sort release by date
        jiraInfoRetrieve.sortReleaseList(releaseList);

        //group commits by release
        gitHubInfoRetrieve.orderCommitsByReleaseDate(allCommits, releaseList);

        jiraInfoRetrieve.sortReleaseList(releaseList);

        //get last commit pre release
        gitHubInfoRetrieve.setReleaseLastCommit(releaseList);

        //dimezzo le release
        List<Release> halfReleaseList = releaseList.subList(0, releaseList.size() / 2);

        //get all tickets
        List<Ticket> allTickets = jiraInfoRetrieve.retrieveTickets(releaseList);

        //per ogni release prendo la lista di tutte le classi java dell ultimo commit della release
        getAllClassesByRelease(releaseList);

        //associo ad ogni ticket i suoi commit, ovvero i commit che lo citano
        associateCommitsToTicket(allCommits,allTickets);

        List<Ticket> toDelete = new ArrayList<>();
        for(Ticket t: allTickets){
            if (t.getAssociatedCommits().isEmpty()){
                toDelete.add(t);
            }
        }
        for(Ticket t: toDelete){
            allTickets.remove(t);
        }

        double proportion = coldStartProportion();

        //assegno i ticket alle release
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

        //scorro tutte le release e assegno i valori di buggyness
        Release lastRelease = releaseList.get(releaseList.size()-1);
        lastRelease.setReleaseAllClass(halfReleaseList.get(halfReleaseList.size()-1).getReleaseAllClass());

        MetricsCalculator metricsCalculator = new MetricsCalculator(gitHubInfoRetrieve);
        metricsCalculator.calculateAll(halfReleaseList);

        for(int i = 1; i < halfReleaseList.size(); i++){
            Release release = halfReleaseList.get(i);
            writeReleaseTrainFile(release,releaseList);
        }
        writeTestFiles(releaseList,halfReleaseList);
        CSVtoARFFConverter.executeConversion(currentProject,halfReleaseList.size());

    }

    //metodo usato per scrivere i train set
    public void writeReleaseTrainFile(Release currRelease, List<Release> releaseList){
        String path = currentProject.toUpperCase()+"_Train_Release_"+currRelease.getId()+".csv";
        List<Release> incrementalReleaseList = releaseList.subList(0, currRelease.getId());
        List<Ticket> releaseTicket;
        releaseTicket = currRelease.getAllReleaseTicket();
        adjustIvTickets(releaseTicket, currRelease.getCurrentProportion(),releaseList);
        writeFile(path,incrementalReleaseList,currRelease,releaseTicket);
    }

    //metodo usato per scrivere i test set
    public void writeTestFiles(List<Release> releaseList,List<Release> halfReleaseList){
        int len = halfReleaseList.size();
        List<Ticket> ticketsForTest = releaseList.get(releaseList.size() - 1).getAllReleaseTicket();
        adjustIvTickets(ticketsForTest, releaseList.get(releaseList.size()-1).getCurrentProportion(), releaseList);

        for(int i = 1; i < len; i++) {
            Release currRelease = releaseList.get(i);
            String path = currentProject.toUpperCase() + "_Test_Release_" + currRelease.getId() + ".csv";
            List<Release> incrementalReleaseList = new ArrayList<>();
            incrementalReleaseList.add(currRelease);

            writeFile(path,incrementalReleaseList,null,ticketsForTest);

        }
    }

    //metodo che scrive il file, chiamato sia per il train sia per il test set
    public void writeFile(String path, List<Release> incrementalReleaseList, Release currRelease, List<Ticket> tickets){
        try (FileWriter writer = new FileWriter(path)) {

            writer.write("CyclomaticComplexity,LineOfCode,LineOfCodeNoComment,NR,Authors,Age,Churn,MeanChurn,MaxChurn," +
                    "LocAdded,MaxLocAdded,Buggy\n");

            //qui scorro tutte le release precedenti a quella corrente, calcolo la buggyness e scrivo il file
            for (Release release : incrementalReleaseList) {
                if (release == currRelease) continue;
                List<Ticket> usableTicket = new ArrayList<>();

                for (Ticket ticket : tickets) {
                    if (ticket.getCalculatedIv().getId() <= release.getId() && ticket.getFv().getId() > release.getId()
                            && ticket.getCalculatedIv().getId() < ticket.getFv().getId()) {
                        usableTicket.add(ticket);
                    }
                }

                assignBuggyness(release, usableTicket);
                List<ClassFile> files = release.getReleaseAllClass();
                for (ClassFile file : files) {
                    //writer.write(file.getPath() + "," + release.getId() + "," + file.getCiclomaticComplexity()
                    writer.write(file.getCiclomaticComplexity()
                            + "," + file.getLineCodeComment() + "," + file.getLineCodeNoComment() + "," + file.getNR() +
                            "," + file.getnAuth() + "," + file.getAge() + "," + file.getChurn() + "," + file.getMeanChurn()
                            + "," + file.getMaxChurn() + "," + file.getLocAdded() + "," + file.getMaxLocAdded() + "," + file.getBuggy() + "\n");
                }
            }
            out.println("File CSV creato con successo.");
        } catch (IOException e) {
            out.println("Si è verificato un errore durante la creazione del file CSV: " + e.getMessage());
        }
    }


    //questo metodo aggiunge la injected version a tutti i ticket che non la possiedono.
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

    //con questo metodo recupero tutti i ticket che hanno una av consistente, utile per il calcolo del proportion
    public List<Ticket> getTicketsWithAv(List<Ticket> allTicket){
        List<Ticket> goodTickets = new ArrayList<>();
        for(Ticket t: allTicket){
            if(t.getIv() != null){
                goodTickets.add(t);
            }
        }
        return goodTickets;
    }

    //questo metodo scorre le release e assegna il valore buggyness delle classi
    private void assignBuggyness(Release release, List<Ticket> releaseTickets){

        List<RevCommit> revCommitList = new ArrayList<>();
        for(Ticket ticket:releaseTickets){
            List<RevCommit> ticketCommits = ticket.getAssociatedCommits();
            for (RevCommit commit : ticketCommits) {
                revCommitList.add(commit);
            }

        }
        sortCommits(revCommitList);

        int len = revCommitList.size();
        for (int i = 1; i < len; i++){
            RevCommit commit = revCommitList.get(i);

            List<String> modifiedClasses = gitHubInfoRetrieve.getDifference(commit,false);
            if (!modifiedClasses.isEmpty()) {
                updateBuggyness(release, modifiedClasses);
            }

        }
    }

    //questo metodo scorre tutti i file modificati da un commit correlato ad un ticket, quindi tali classi
    //si assumono buggy e quindi deve essere settato il parametro buggy a true
    private void updateBuggyness(Release release, List<String> allPath){

        for(String path: allPath){
            ClassFile file = release.getClassFileByPath(path);
            if(file != null){
                file.setBuggy(true);
            }
        }
    }

    //un metodo utile per ordinare i commit in ordine temporale
    private void sortCommits(List<RevCommit> commits){
        Collections.sort(commits,new RevCommitComparator());
    }

    //il comparator utile a sortCommits
    private class RevCommitComparator implements java.util.Comparator<RevCommit> {
        @Override
        public int compare(RevCommit a, RevCommit b) {
            return a.getCommitterIdent().getWhen().compareTo(b.getCommitterIdent().getWhen());
        }
    }

    //metodo che data una lista di commits e una di tickets, assegna ad ogni ticket i commit che sono correlati a quel ticket
    //ovvero i commit che citano i ticket nel loro commento
    private void associateCommitsToTicket(List<RevCommit> allCommits, List<Ticket> allTickets){

        out.println("\n\n********************BEGIN ASSOCIATION COMMITS TO TICKETS********************");
        for(RevCommit commit:allCommits){
            String comment = commit.getFullMessage();
            for (Ticket ticket:allTickets){
                if (comment.contains(ticket.getKey() + ":") || comment.contains(ticket.getKey() + "]")
                        || comment.contains(ticket.getKey() + " ") && !ticket.getAssociatedCommits().contains(commit)) {
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
