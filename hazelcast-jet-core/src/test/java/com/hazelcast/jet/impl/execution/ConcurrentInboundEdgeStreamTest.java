/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.impl.execution;

import com.hazelcast.internal.util.concurrent.ConcurrentConveyor;
import com.hazelcast.internal.util.concurrent.OneToOneConcurrentArrayQueue;
import com.hazelcast.jet.Watermark;
import com.hazelcast.jet.impl.util.ProgressState;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.hazelcast.jet.impl.execution.DoneItem.DONE_ITEM;
import static com.hazelcast.jet.impl.util.ProgressState.DONE;
import static com.hazelcast.jet.impl.util.ProgressState.MADE_PROGRESS;
import static com.hazelcast.jet.impl.util.ProgressState.NO_PROGRESS;
import static com.hazelcast.jet.impl.util.ProgressState.WAS_ALREADY_DONE;
import static org.junit.Assert.assertEquals;

@Category(QuickTest.class)
@RunWith(HazelcastParallelClassRunner.class)
public class ConcurrentInboundEdgeStreamTest {

    private static final Object senderGone = new Object();

    @Rule
    public ExpectedException exception = ExpectedException.none();

    private OneToOneConcurrentArrayQueue<Object> q1;
    private OneToOneConcurrentArrayQueue<Object> q2;
    private ConcurrentInboundEdgeStream stream;
    private ConcurrentConveyor<Object> conveyor;

    @Before
    public void setUp() {
        q1 = new OneToOneConcurrentArrayQueue<>(128);
        q2 = new OneToOneConcurrentArrayQueue<>(128);
        //noinspection unchecked
        conveyor = ConcurrentConveyor.concurrentConveyor(senderGone, q1, q2);

        stream = new ConcurrentInboundEdgeStream(conveyor, 0, 0, -1, false);
    }

    @Test
    public void when_twoEmittersOneDoneFirst_then_madeProgress() {
        add(q1, 1, 2, DONE_ITEM);
        add(q2, 6);
        drainAndAssert(MADE_PROGRESS, 1, 2, 6);

        add(q2, 7, DONE_ITEM);
        drainAndAssert(DONE, 7);

        // both emitters are now done and made no progress since last call
        drainAndAssert(WAS_ALREADY_DONE);
    }

    @Test
    public void when_twoEmittersDrainedAtOnce_then_firstCallDone() {
        add(q1, 1, 2, DONE_ITEM);
        add(q2, 6, DONE_ITEM);
        // emitter1 returned 1 and 2; emitter2 returned 6
        // both are now done
        drainAndAssert(DONE, 1, 2, 6);
    }

    @Test
    public void when_allEmittersInitiallyDone_then_firstCallDone() {
        q1.add(DONE_ITEM);
        q2.add(DONE_ITEM);
        drainAndAssert(DONE);
        drainAndAssert(WAS_ALREADY_DONE);
    }

    @Test
    public void when_oneEmitterWithNoProgress_then_noProgress() {
        add(q2, 1, DONE_ITEM);
        drainAndAssert(MADE_PROGRESS, 1);

        // now emitter2 is done, emitter1 is not but has no progress
        drainAndAssert(NO_PROGRESS);

        // now make emitter1 done, without returning anything
        q1.add(DONE_ITEM);

        drainAndAssert(DONE);
        drainAndAssert(WAS_ALREADY_DONE);
    }

    @Test
    public void when_receivingWatermarks_then_coalesce() {
        add(q1, wm(1));
        add(q2, wm(2));
        drainAndAssert(MADE_PROGRESS, wm(1));

        add(q1, wm(3));
        add(q2, wm(3));
        drainAndAssert(MADE_PROGRESS, wm(3));
    }

    @Test
    public void when_receivingSnapshots_then_coalesce() {
        add(q1, barrier(0));
        add(q2, 1);
        drainAndAssert(MADE_PROGRESS, 1);

        add(q1, 2);
        add(q2, barrier(0));
        drainAndAssert(MADE_PROGRESS, 2, barrier(0));
    }

    @Test
    public void when_receivingSnapshots_then_waitForSnapshot() {
        stream = new ConcurrentInboundEdgeStream(conveyor, 0, 0, -1, true);

        add(q1, barrier(0));
        add(q2, 1);
        drainAndAssert(MADE_PROGRESS, 1);

        add(q1, 2);
        drainAndAssert(NO_PROGRESS);

        add(q2, barrier(0));
        drainAndAssert(MADE_PROGRESS, barrier(0));
        drainAndAssert(MADE_PROGRESS, 2);
    }

    @Test
    public void when_receivingSnapshotsWhileDone_then_coalesce() {
        stream = new ConcurrentInboundEdgeStream(conveyor, 0, 0, -1, true);

        add(q1, 1, barrier(0));
        add(q2, DONE_ITEM);
        drainAndAssert(MADE_PROGRESS, 1, barrier(0));

        add(q1, DONE_ITEM);
        drainAndAssert(DONE);
    }

    @Test
    public void when_receiveOnlyBarrierAndDoneItemFromSameQueue_then_coalesce() {
        add(q1, 1, barrier(0), DONE_ITEM);
        drainAndAssert(MADE_PROGRESS, 1);
        drainAndAssert(MADE_PROGRESS);

        add(q2, barrier(0));
        drainAndAssert(MADE_PROGRESS, barrier(0));
    }

    private void drainAndAssert(ProgressState expectedState, Object... expectedItems) {
        List<Object> list = new ArrayList<>();
        assertEquals("progressState", expectedState, stream.drainTo(list::add));
        assertEquals(Arrays.asList(expectedItems), list);
    }

    private void add(OneToOneConcurrentArrayQueue<Object> q, Object... items) {
        q.addAll(Arrays.asList(items));
    }

    private Watermark wm(long timestamp) {
        return new Watermark(timestamp);
    }

    private SnapshotBarrier barrier(long snapshotId) {
        return new SnapshotBarrier(snapshotId);
    }
}
