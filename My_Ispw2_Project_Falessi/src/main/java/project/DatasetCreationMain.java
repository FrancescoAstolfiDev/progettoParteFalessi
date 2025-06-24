package project;

import project.controllers.MethodDataSetExecutor;



public class DatasetCreationMain {

	public static void main(String[] args) throws Exception {

		//scegliere il progetto tra "openjpa" e "bookeeper"
		String projectName = "bookkeeper";

		MethodDataSetExecutor mainFlow = new MethodDataSetExecutor(projectName);
		mainFlow.executeFlow();

	}
}