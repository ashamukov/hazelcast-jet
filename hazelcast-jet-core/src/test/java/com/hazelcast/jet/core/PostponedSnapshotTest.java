/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.core;

import com.hazelcast.jet.IMapJet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.impl.SnapshotRepository;
import com.hazelcast.jet.impl.execution.SnapshotRecord;
import com.hazelcast.jet.impl.execution.SnapshotRecord.SnapshotStatus;
import com.hazelcast.test.HazelcastSerialClassRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.Edge.from;
import static com.hazelcast.jet.core.processor.DiagnosticProcessors.writeLoggerP;
import static com.hazelcast.jet.impl.execution.SnapshotRecord.SnapshotStatus.ONGOING;
import static com.hazelcast.jet.impl.execution.SnapshotRecord.SnapshotStatus.SUCCESSFUL;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
public class PostponedSnapshotTest extends JetTestSupport {

    private static volatile AtomicIntegerArray latches;

    private JetInstance instance;

    @Before
    public void setup() {
        instance = createJetMember();
        latches = new AtomicIntegerArray(2);
    }

    @Test
    public void when_jobHasHigherPriorityEdge_then_noSnapshotUntilEdgeDone() {
        Job job = startJob();

        latches.set(0, 1);
        IMapJet<Object, Object> snapshotsMap = instance.getMap(SnapshotRepository.snapshotsMapName(job.getId()));
        assertTrueEventually(() -> assertTrue(existsSnapshot(snapshotsMap, SUCCESSFUL)), 5);

        // finish the job
        latches.set(1, 1);
        job.join();
    }

    @Test
    public void when_gracefulShutdownWhilePostponed_then_shutsDown() {
        startJob();

        // When
        Future shutdownFuture = spawn(() -> instance.shutdown());
        assertTrueAllTheTime(() -> assertFalse(shutdownFuture.isDone()), 3);

        // finish the job
        latches.set(0, 1);

        // Then
        assertTrueEventually(() -> assertTrue(shutdownFuture.isDone()));
    }

    private Job startJob() {
        DAG dag = new DAG();
        Vertex highPrioritySource = dag.newVertex("highPrioritySource", () -> new SourceP(0)).localParallelism(1);
        Vertex lowPrioritySource = dag.newVertex("lowPrioritySource", () -> new SourceP(1)).localParallelism(1);
        Vertex sink = dag.newVertex("sink", writeLoggerP());

        dag.edge(between(highPrioritySource, sink).priority(-1))
           .edge(from(lowPrioritySource).to(sink, 1));

        JobConfig config = new JobConfig();
        config.setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE);
        config.setSnapshotIntervalMillis(100);

        Job job = instance.newJob(dag, config);
        IMapJet<Object, Object> snapshotsMap = instance.getMap(SnapshotRepository.snapshotsMapName(job.getId()));

        // check, that snapshot starts, but stays in ONGOING state
        assertTrueEventually(() -> assertTrue(existsSnapshot(snapshotsMap, ONGOING)), 5);
        assertTrueAllTheTime(() -> {
            assertTrue(existsSnapshot(snapshotsMap, ONGOING));
            assertFalse(existsSnapshot(snapshotsMap, SUCCESSFUL));
        }, 2);
        return job;
    }

    private boolean existsSnapshot(IMapJet<Object, Object> snapshotsMap, SnapshotStatus state) {
        return snapshotsMap.entrySet().stream()
                           .filter(e -> e.getValue() instanceof SnapshotRecord)
                           .map(e -> (SnapshotRecord) e.getValue())
                           .anyMatch(rec -> rec.status() == state);
    }

    private static final class SourceP extends AbstractProcessor {
        private final int latchIndex;

        SourceP(int latchIndex) {
            this.latchIndex = latchIndex;
        }

        @Override
        public boolean complete() {
            return latches.get(latchIndex) != 0;
        }

        @Override
        public boolean saveToSnapshot() {
            return tryEmitToSnapshot(latchIndex, latchIndex);
        }
    }
}
