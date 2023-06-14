package project.models;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Release {

	private int id;
	private String name;
	private Date date;
	private double currentProportion;
	private List<Ticket> allReleaseTicket;
	private List<RevCommit> allReleaseCommits;
	private RevCommit lastCommitPreRelease;

	private List<ClassFile> releaseAllClass;
	
	public Release(int id, String name, Date date) {
		this.id = id;
		this.name = name;
		this.date = date;
		this.allReleaseCommits = new ArrayList<>();
		this.lastCommitPreRelease = null;
		this.releaseAllClass = new ArrayList<>();
	}
	public void setAllReleaseTicket(List<Ticket> tickets){
		this.allReleaseTicket = tickets;
	}

	public List<Ticket> getAllReleaseTicket(){
		return this.allReleaseTicket;
	}
	public double getCurrentProportion(){
		return this.currentProportion;
	}
	public ClassFile getClassFileByPath(String path){
		for (ClassFile file: releaseAllClass){
			if(file.getPath().equals(path)){
				return file;
			}
		}
		return null;
	}
	public void setCurrentProportion(double proportion){
		this.currentProportion = proportion;
	}
	public void addClassFile(ClassFile classFile){
		this.releaseAllClass.add(classFile);
	}

	public List<ClassFile> getReleaseAllClass(){
		return this.releaseAllClass;
	}
	public void setReleaseAllClass(List<ClassFile> allClass){
		this.releaseAllClass = allClass;
	}

	public void setLastCommitPreRelease(RevCommit commit){
		this.lastCommitPreRelease = commit;
	}

	public RevCommit getLastCommitPreRelease(){
		return this.lastCommitPreRelease;
	}

	public void addCommitToReleaseList(RevCommit commit){
		this.allReleaseCommits.add(commit);
	}

	public List<RevCommit> getAllReleaseCommits(){
		return this.allReleaseCommits;
	}

	/**
	 * @return the id
	 */
	public int getId() {
		return id;
	}

	/**
	 * @param id the id to set
	 */
	public void setId(int id) {
		this.id = id;
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the date
	 */
	public Date getDate() {
		return date;
	}

	/**
	 * @param date the date to set
	 */
	public void setDate(Date date) {
		this.date = date;
	}
	
}
