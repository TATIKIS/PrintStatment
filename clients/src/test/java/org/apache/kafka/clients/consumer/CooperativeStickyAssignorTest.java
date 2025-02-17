/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.Subscription;
import org.apache.kafka.clients.consumer.internals.AbstractStickyAssignor;
import org.apache.kafka.clients.consumer.internals.AbstractStickyAssignorTest;
import org.apache.kafka.common.TopicPartition;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.apache.kafka.clients.consumer.internals.AbstractStickyAssignor.DEFAULT_GENERATION;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Collections.emptyList;

public class CooperativeStickyAssignorTest extends AbstractStickyAssignorTest {

    @Override
    public AbstractStickyAssignor createAssignor() {
        return new CooperativeStickyAssignor();
    }

    @Override
    public Subscription buildSubscriptionV0(List<String> topics, List<TopicPartition> partitions, int generationId) {
        // cooperative sticky assignor only supports ConsumerProtocolSubscription V1 or above
        return null;
    }

    @Override
    public Subscription buildSubscriptionV1(List<String> topics, List<TopicPartition> partitions, int generationId) {
        assignor.onAssignment(new ConsumerPartitionAssignor.Assignment(partitions), new ConsumerGroupMetadata(groupId, generationId, consumer1, Optional.empty()));
        return new Subscription(topics, assignor.subscriptionUserData(new HashSet<>(topics)), partitions, DEFAULT_GENERATION);
    }

    @Override
    public Subscription buildSubscriptionV2Above(List<String> topics, List<TopicPartition> partitions, int generationId) {
        return new Subscription(topics, assignor.subscriptionUserData(new HashSet<>(topics)), partitions, generationId);
    }

    @Override
    public ByteBuffer generateUserData(List<String> topics, List<TopicPartition> partitions, int generation) {
        assignor.onAssignment(new ConsumerPartitionAssignor.Assignment(partitions), new ConsumerGroupMetadata(groupId, generationId, consumer1, Optional.empty()));
        return assignor.subscriptionUserData(new HashSet<>(topics));
    }

    @Test
    public void testEncodeAndDecodeGeneration() {
        Subscription subscription = new Subscription(topics(topic), assignor.subscriptionUserData(new HashSet<>(topics(topic))));

        Optional<Integer> encodedGeneration = ((CooperativeStickyAssignor) assignor).memberData(subscription).generation;
        assertTrue(encodedGeneration.isPresent());
        assertEquals(encodedGeneration.get(), DEFAULT_GENERATION);

        int generation = 10;
        assignor.onAssignment(null, new ConsumerGroupMetadata("dummy-group-id", generation, "dummy-member-id", Optional.empty()));

        subscription = new Subscription(topics(topic), assignor.subscriptionUserData(new HashSet<>(topics(topic))));
        encodedGeneration = ((CooperativeStickyAssignor) assignor).memberData(subscription).generation;

        assertTrue(encodedGeneration.isPresent());
        assertEquals(encodedGeneration.get(), generation);
    }

    @Test
    public void testDecodeGeneration() {
        Subscription subscription = new Subscription(topics(topic));
        assertFalse(((CooperativeStickyAssignor) assignor).memberData(subscription).generation.isPresent());
    }

    @Test
    public void testAllConsumersHaveOwnedPartitionInvalidatedWhenClaimedByMultipleConsumersInSameGenerationWithEqualPartitionsPerConsumer() {
        Map<String, Integer> partitionsPerTopic = new HashMap<>();
        partitionsPerTopic.put(topic, 3);

        subscriptions.put(consumer1, buildSubscriptionV2Above(topics(topic), partitions(tp(topic, 0), tp(topic, 1)), generationId));
        subscriptions.put(consumer2, buildSubscriptionV2Above(topics(topic), partitions(tp(topic, 0), tp(topic, 2)), generationId));
        subscriptions.put(consumer3, buildSubscriptionV2Above(topics(topic), emptyList(), generationId));

        Map<String, List<TopicPartition>> assignment = assignor.assign(partitionsPerTopic, subscriptions);
        assertEquals(partitions(tp(topic, 1)), assignment.get(consumer1));
        assertEquals(partitions(tp(topic, 2)), assignment.get(consumer2));
        // In the cooperative assignor, topic-0 has to be considered "owned" and so it cant be assigned until both have "revoked" it
        assertTrue(assignment.get(consumer3).isEmpty());

        verifyValidityAndBalance(subscriptions, assignment, partitionsPerTopic);
        assertTrue(isFullyBalanced(assignment));
    }

    @Test
    public void testAllConsumersHaveOwnedPartitionInvalidatedWhenClaimedByMultipleConsumersInSameGenerationWithUnequalPartitionsPerConsumer() {
        Map<String, Integer> partitionsPerTopic = new HashMap<>();
        partitionsPerTopic.put(topic, 4);

        subscriptions.put(consumer1, buildSubscriptionV2Above(topics(topic), partitions(tp(topic, 0), tp(topic, 1)), generationId));
        subscriptions.put(consumer2, buildSubscriptionV2Above(topics(topic), partitions(tp(topic, 0), tp(topic, 2)), generationId));
        subscriptions.put(consumer3, buildSubscriptionV2Above(topics(topic), emptyList(), generationId));

        Map<String, List<TopicPartition>> assignment = assignor.assign(partitionsPerTopic, subscriptions);
        assertEquals(partitions(tp(topic, 1), tp(topic, 3)), assignment.get(consumer1));
        assertEquals(partitions(tp(topic, 2)), assignment.get(consumer2));
        // In the cooperative assignor, topic-0 has to be considered "owned" and so it cant be assigned until both have "revoked" it
        assertTrue(assignment.get(consumer3).isEmpty());

        verifyValidityAndBalance(subscriptions, assignment, partitionsPerTopic);
        assertTrue(isFullyBalanced(assignment));
    }

    @Test
    public void testMemberDataWithInconsistentData() {
        Map<String, Integer> partitionsPerTopic = new HashMap<>();
        partitionsPerTopic.put(topic, 2);
        List<TopicPartition> ownedPartitionsInUserdata = partitions(tp1);
        List<TopicPartition> ownedPartitionsInSubscription = partitions(tp0);

        assignor.onAssignment(new ConsumerPartitionAssignor.Assignment(ownedPartitionsInUserdata), new ConsumerGroupMetadata(groupId, generationId, consumer1, Optional.empty()));
        ByteBuffer userDataWithHigherGenerationId = assignor.subscriptionUserData(new HashSet<>(topics(topic)));
        // The owned partitions and generation id are provided in user data and different owned partition is provided in subscription without generation id
        // If subscription provides no generation id, we'll honor the generation id in userData and owned partitions in subscription
        Subscription subscription = new Subscription(topics(topic), userDataWithHigherGenerationId, ownedPartitionsInSubscription);

        AbstractStickyAssignor.MemberData memberData = memberData(subscription);
        // In CooperativeStickyAssignor, we only serialize generation id into userData
        assertEquals(ownedPartitionsInSubscription, memberData.partitions, "subscription: " + subscription + " doesn't have expected owned partition");
        assertEquals(generationId, memberData.generation.orElse(-1), "subscription: " + subscription + " doesn't have expected generation id");
    }

    @Test
    public void testMemberDataWithEmptyPartitionsAndEqualGeneration() {
        List<String> topics = topics(topic);
        List<TopicPartition> ownedPartitions = partitions(tp(topic1, 0), tp(topic2, 1));

        // subscription containing empty owned partitions and the same generation id, and non-empty owned partition in user data,
        // member data should honor the one in subscription since cooperativeStickyAssignor only supports ConsumerProtocolSubscription v1 and above
        Subscription subscription = new Subscription(topics, generateUserData(topics, ownedPartitions, generationId), Collections.emptyList(), generationId);

        AbstractStickyAssignor.MemberData memberData = memberData(subscription);
        assertEquals(Collections.emptyList(), memberData.partitions, "subscription: " + subscription + " doesn't have expected owned partition");
        assertEquals(generationId, memberData.generation.orElse(-1), "subscription: " + subscription + " doesn't have expected generation id");
    }

    @Test
    public void testMemberDataWithEmptyPartitionsAndHigherGeneration() {
        List<String> topics = topics(topic);
        List<TopicPartition> ownedPartitions = partitions(tp(topic1, 0), tp(topic2, 1));

        // subscription containing empty owned partitions and a higher generation id, and non-empty owned partition in user data,
        // member data should honor the one in subscription since generation id is higher
        Subscription subscription = new Subscription(topics, generateUserData(topics, ownedPartitions, generationId - 1), Collections.emptyList(), generationId);

        AbstractStickyAssignor.MemberData memberData = memberData(subscription);
        assertEquals(Collections.emptyList(), memberData.partitions, "subscription: " + subscription + " doesn't have expected owned partition");
        assertEquals(generationId, memberData.generation.orElse(-1), "subscription: " + subscription + " doesn't have expected generation id");
    }

    @Test
    public void testAssignorWithOldVersionSubscriptions() {
        Map<String, Integer> partitionsPerTopic = new HashMap<>();
        partitionsPerTopic.put(topic1, 3);

        List<String> subscribedTopics = topics(topic1);

        // cooperative sticky assignor only supports ConsumerProtocolSubscription V1 or above
        subscriptions.put(consumer1, buildSubscriptionV1(subscribedTopics, partitions(tp(topic1, 0)), generationId));
        subscriptions.put(consumer2, buildSubscriptionV1(subscribedTopics, partitions(tp(topic1, 1)), generationId));
        subscriptions.put(consumer3, buildSubscriptionV2Above(subscribedTopics, Collections.emptyList(), generationId));

        Map<String, List<TopicPartition>> assignment = assignor.assign(partitionsPerTopic, subscriptions);
        assertEquals(partitions(tp(topic1, 0)), assignment.get(consumer1));
        assertEquals(partitions(tp(topic1, 1)), assignment.get(consumer2));
        assertEquals(partitions(tp(topic1, 2)), assignment.get(consumer3));

        verifyValidityAndBalance(subscriptions, assignment, partitionsPerTopic);
        assertTrue(isFullyBalanced(assignment));
    }

    /**
     * The cooperative assignor must do some additional work and verification of some assignments relative to the eager
     * assignor, since it may or may not need to trigger a second follow-up rebalance.
     * <p>
     * In addition to the validity requirements described in
     * {@link org.apache.kafka.clients.consumer.internals.AbstractStickyAssignorTest#verifyValidityAndBalance(Map, Map, Map)},
     * we must verify that no partition is being revoked and reassigned during the same rebalance. This means the initial
     * assignment may be unbalanced, so if we do detect partitions being revoked we should trigger a second "rebalance"
     * to get the final assignment and then verify that it is both valid and balanced.
     */
    @Override
    public void verifyValidityAndBalance(Map<String, Subscription> subscriptions,
                                         Map<String, List<TopicPartition>> assignments,
                                         Map<String, Integer> partitionsPerTopic) {
        int rebalances = 0;
        // partitions are being revoked, we must go through another assignment to get the final state
        while (verifyCooperativeValidity(subscriptions, assignments)) {

            // update the subscriptions with the now owned partitions
            for (Map.Entry<String, List<TopicPartition>> entry : assignments.entrySet()) {
                String consumer = entry.getKey();
                Subscription oldSubscription = subscriptions.get(consumer);
                subscriptions.put(consumer, buildSubscriptionV2Above(oldSubscription.topics(), entry.getValue(), generationId));
            }

            assignments.clear();
            assignments.putAll(assignor.assign(partitionsPerTopic, subscriptions));
            ++rebalances;

            assertTrue(rebalances <= 4);
        }

        // Check the validity and balance of the final assignment
        super.verifyValidityAndBalance(subscriptions, assignments, partitionsPerTopic);
    }

    // Returns true if partitions are being revoked, indicating a second rebalance will be triggered
    private boolean verifyCooperativeValidity(Map<String, Subscription> subscriptions, Map<String, List<TopicPartition>> assignments) {
        Set<TopicPartition> allAddedPartitions = new HashSet<>();
        Set<TopicPartition> allRevokedPartitions = new HashSet<>();
        for (Map.Entry<String, List<TopicPartition>> entry : assignments.entrySet()) {
            List<TopicPartition> ownedPartitions = subscriptions.get(entry.getKey()).ownedPartitions();
            List<TopicPartition> assignedPartitions = entry.getValue();

            Set<TopicPartition> revokedPartitions = new HashSet<>(ownedPartitions);
            revokedPartitions.removeAll(assignedPartitions);

            Set<TopicPartition> addedPartitions = new HashSet<>(assignedPartitions);
            addedPartitions.removeAll(ownedPartitions);

            allAddedPartitions.addAll(addedPartitions);
            allRevokedPartitions.addAll(revokedPartitions);
        }

        Set<TopicPartition> intersection = new HashSet<>(allAddedPartitions);
        intersection.retainAll(allRevokedPartitions);
        assertTrue(intersection.isEmpty(),
            "Error: Some partitions were assigned to a new consumer during the same rebalance they are being " +
            "revoked from their previous owner. Partitions: " + intersection);

        return !allRevokedPartitions.isEmpty();
    }
}
