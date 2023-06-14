package project.models;


import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;

public class Ticket {

	private String key;
	private Release iv;
	private Release ov;
	private Release fv;
	private Release calculatedIv;
	private List<RevCommit> associatedCommits;

	public Ticket(String key, Release ov, Release fv, Release av) {
		this.key = key;
		this.iv = av;
		this.ov = ov;
		this.fv = fv;
		this.associatedCommits = new ArrayList<>();
	}

	public void setCalculatedIv(Release calculatedIv) {
		this.calculatedIv = calculatedIv;
	}
	public Release getCalculatedIv(){
		return this.calculatedIv;
	}

	public List<RevCommit> getAssociatedCommits(){
		return this.associatedCommits;
	}

	public void addAssociatedCommit(RevCommit commit){
		this.associatedCommits.add(commit);
	}
	/**
	 * @return the key
	 */
	public String getKey() {
		return key;
	}

	/**
	 * @param key the key to set
	 */
	public void setKey(String key) {
		this.key = key;
	}

	/**
	 * @return the iv
	 */
	public Release getIv() {
		if(iv != null){
			return iv;
		}
		return null;
	}

	/**
	 * @param iv the iv to set
	 */
	public void setIv(Release iv) {
		this.iv = iv;
	}

	/**
	 * @return the ov
	 */
	public Release getOv() {
		if(ov != null){
			return ov;
		}
		return null;	}

	/**
	 * @param ov the ov to set
	 */
	public void setOv(Release ov) {
		this.ov = ov;
	}

	/**
	 * @return the fv
	 */
	public Release getFv() {
		if(fv != null){
			return fv;
		}
		return null;
	}

	/**
	 * @param fv the fv to set
	 */
	public void setFv(Release fv) {
		this.fv = fv;
	}

}
