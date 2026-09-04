package com.sce.ui;

public interface EvaluationCallback {
    void onStatusUpdate(String status);
    void onConsoleOutput(String text, boolean isError);
    void onAIHintReceived(String hint, String suggestedFix);
    void onEvaluationComplete();
}
