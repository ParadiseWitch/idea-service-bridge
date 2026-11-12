package dev.agentbridge.core;

public record BridgeRequest(String project, String configuration, long executionId,
                            String operation, String caller, String executor) {
    public void validate() {
        if (project == null || project.isBlank() || project.length()>1024 ||
            configuration == null || configuration.isBlank() || configuration.length()>256 ||
            caller == null || caller.isBlank() || caller.length()>128) {
            throw new IllegalArgumentException("project, configuration and caller are required");
        }
        if (!java.util.Set.of("start","update-resources","update-classes-and-resources","restart").contains(operation == null ? "" : operation))
            throw new IllegalArgumentException("Unknown operation");
        if("start".equals(operation)) {
            if(executionId!=0) throw new IllegalArgumentException("start requires executionId omitted or zero");
            if(executor!=null && !java.util.Set.of("Run","Debug").contains(executor))
                throw new IllegalArgumentException("executor must be Run or Debug (default Debug)");
        } else if(executionId<=0) throw new IllegalArgumentException("Positive executionId required for an existing instance");
    }
}
