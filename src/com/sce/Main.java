package com.sce;

import com.sce.core.EvaluationRequest;

public class Main {
    
    // Test code with a deliberate runtime error (Divide by Zero)
    private static final String BROKEN_CODE =
            "public class BrokenCode {\n" +
            "    public static void main(String[] args) {\n" +
            "        System.out.println(\"Attempting division...\");\n" +
            "        int result = 10 / 0;\n" +
            "    }\n" +
            "}\n";

    public static void main(String[] args) {
        // 1. Initialize the controller
        SystemController controller = new SystemController();
        
        // 2. Start the background worker thread
        controller.start();

        System.out.println("[Main] Submitting request to controller...");
        
        // 3. Submit the request (this will return immediately)
        controller.handleSubmission(new EvaluationRequest("BrokenCode", BROKEN_CODE));
        
        System.out.println("[Main] Request submitted successfully! The main UI thread is now completely free.");

        // 4. Sleep the main thread for a few seconds to let the background daemon thread finish its work 
        // (Since the worker is a daemon thread, if Main exits immediately, the worker dies too).
        // API response aane tak main thread ko 10 seconds rok kar rakhte hain
        try {
            System.out.println("[Main] Waiting 30 seconds for background evaluation to complete...");
            Thread.sleep(30000); 
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Ab aaram se shutdown karo
        controller.shutdown();
    }
}