package project;

import project.controllers.MethodDataSetExecutor;



public class DatasetCreationMain {

	public static void main(String[] args) throws Exception {

 	//scegliere il progetto tra "openjpa" e "bookkeeper"
		String projectName = Projects.BOOKKEEPER.toString();

		MethodDataSetExecutor mainFlow = new MethodDataSetExecutor(projectName);
		mainFlow.executeFlow();

	}
}
