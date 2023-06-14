package project;


import project.controllers.DataSetExecutor;



public class DatasetCreationMain {

	public static void main(String[] args) throws Exception {

		//scegliere il progetto tra "openjpa" e "bookeeper"
		String projectName = "bookkeeper";

		DataSetExecutor mainFlow = new DataSetExecutor(projectName);
		mainFlow.executeFlow();

	}


}
