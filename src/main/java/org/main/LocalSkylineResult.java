package org.main;

import java.io.Serializable;
import java.util.List;

public class LocalSkylineResult implements Serializable {
    public int partitionId;
    public String queryPayload;
    public long triggerTimestamp;
    public long partitionStartTime;
    public List<ServiceTuple> skylinePoints;
    public long cpuTimeMillis;

    public LocalSkylineResult() {}

    public LocalSkylineResult(int partitionId, String queryPayload, long triggerTimestamp,
                              long partitionStartTime, List<ServiceTuple> skylinePoints,
                              long cpuTimeMillis) {
        this.partitionId = partitionId;
        this.queryPayload = queryPayload;
        this.triggerTimestamp = triggerTimestamp;
        this.partitionStartTime = partitionStartTime;
        this.skylinePoints = skylinePoints;
        this.cpuTimeMillis = cpuTimeMillis;
    }

    @Override
    public String toString() {
        return "LocalSkylineResult{" +
                "partitionId=" + partitionId +
                ", queryPayload='" + queryPayload + '\'' +
                ", triggerTimestamp=" + triggerTimestamp +
                ", partitionStartTime=" + partitionStartTime +
                ", skylinePoints=" + skylinePoints +
                ", cpuTimeMillis=" + cpuTimeMillis +
                '}';
    }
}
