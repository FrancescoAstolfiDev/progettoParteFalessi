package project.models;


import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;

public class Ticket {

	private String key;
	private IRelease iv;
	private IRelease ov;
	private IRelease fv;
	private IRelease calculatedIv;
	private List<RevCommit> associatedCommits;

	public Ticket(String key, IRelease ov, IRelease fv, IRelease av) {
		this.key = key;
		this.iv = av;
		this.ov = ov;
		this.fv = fv;
		this.associatedCommits = new ArrayList<>();

	}

	public void setCalculatedIv(IRelease calculatedIv) {

		this.calculatedIv = calculatedIv;

	}
	public IRelease getCalculatedIv(){

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
	public IRelease getIv() {
		if(iv != null){
			return iv;
		}
		return null;
	}

	/**
	 * @param iv the iv to set
	 */
	public void setIv(IRelease iv) {
		this.iv = iv;
	}

	/**
	 * @return the ov
	 */
	public IRelease getOv() {
		if(ov != null){
			return ov;
		}
		return null;	}

	/**
	 * @param ov the ov to set
	 */
	public void setOv(IRelease ov) {
		this.ov = ov;
	}

	/**
	 * @return the fv
	 */
	public IRelease getFv() {
		if(fv != null){
			return fv;
		}
		return null;
	}

	/**
	 * @param fv the fv to set
	 */
	public void setFv(IRelease fv) {
		this.fv = fv;
	}
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();

		builder.append("Ticket ").append(key);

		// Aggiungi informazioni sulle release
		builder.append("\n  IV: ").append(iv != null ? iv.getId() + " (" + iv.getName() + ")" : "null");
		builder.append("\n  OV: ").append(ov != null ? ov.getId() + " (" + ov.getName() + ")" : "null");
		builder.append("\n  FV: ").append(fv != null ? fv.getId() + " (" + fv.getName() + ")" : "null");
		builder.append("\n  CalcIV: ").append(calculatedIv != null ? calculatedIv.getId() + " (" + calculatedIv.getName() + ")" : "null");

		return builder.toString();
	}

}
