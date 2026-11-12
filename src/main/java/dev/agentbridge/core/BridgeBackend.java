package dev.agentbridge.core;

import java.util.*;
import java.util.concurrent.CompletionStage;

public interface BridgeBackend extends AutoCloseable {
    List<Map<String,Object>> configurations() throws Exception;
    CompletionStage<Map<String,Object>> execute(BridgeRequest request) throws Exception;
    @Override void close();
}
