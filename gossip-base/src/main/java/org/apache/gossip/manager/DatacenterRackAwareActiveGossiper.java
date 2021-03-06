/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gossip.manager;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.gossip.LocalMember;

import com.codahale.metrics.MetricRegistry;

/**
 * Sends gossip traffic at different rates to other racks and data-centers.
 * This implementation controls the rate at which gossip traffic is shared. 
 * There are two constructs Datacenter and Rack. It is assumed that bandwidth and latency is higher
 * in the rack than in the the datacenter. We can adjust the rate at which we send messages to each group.
 * 以不同的速率向不同的机架或者数据中心发送gossip心跳。
 * 当前实现控制共享gossip心跳的速率。假设不同机架或机房的延迟和宽带比同一数据中心的高。
 * 我们仅调整发送每个组的消息速率。
 * 
 */
public class DatacenterRackAwareActiveGossiper extends AbstractActiveGossiper {

  /**
   * 数据中心
   */
  public static final String DATACENTER = "datacenter";
  /**
   * 机架，或机房
   */
  public static final String RACK = "rack";

  /**
   * 同机架gossip间隔
   */
  private int sameRackGossipIntervalMs = 100;
  /**
   * 同数据中心gossip间隔
   */
  private int sameDcGossipIntervalMs = 500;
  /**
   * 不同数据中心gossip间隔
   */
  private int differentDatacenterGossipIntervalMs = 1000;
  private int randomDeadMemberSendIntervalMs = 250;
  
  private ScheduledExecutorService scheduledExecutorService;
  private final BlockingQueue<Runnable> workQueue;
  private ThreadPoolExecutor threadService;
  
  public DatacenterRackAwareActiveGossiper(GossipManager gossipManager, GossipCore gossipCore,
          MetricRegistry registry) {
    super(gossipManager, gossipCore, registry);
    scheduledExecutorService = Executors.newScheduledThreadPool(2);
    workQueue = new ArrayBlockingQueue<Runnable>(1024);
    threadService = new ThreadPoolExecutor(1, 30, 1, TimeUnit.SECONDS, workQueue,
            new ThreadPoolExecutor.DiscardOldestPolicy());
    try {
      sameRackGossipIntervalMs = Integer.parseInt(gossipManager.getSettings()
              .getActiveGossipProperties().get("sameRackGossipIntervalMs"));
    } catch (RuntimeException ex) { }
    try {
      sameDcGossipIntervalMs = Integer.parseInt(gossipManager.getSettings()
              .getActiveGossipProperties().get("sameDcGossipIntervalMs"));
    } catch (RuntimeException ex) { }
    try {
      differentDatacenterGossipIntervalMs = Integer.parseInt(gossipManager.getSettings()
              .getActiveGossipProperties().get("differentDatacenterGossipIntervalMs"));
    } catch (RuntimeException ex) { }
    try {
      randomDeadMemberSendIntervalMs = Integer.parseInt(gossipManager.getSettings()
              .getActiveGossipProperties().get("randomDeadMemberSendIntervalMs"));
    } catch (RuntimeException ex) { }
  }

  @Override
  public void init() {
    super.init();
    //same rack //同机房机架
    scheduledExecutorService.scheduleAtFixedRate(() ->
      threadService.execute(() -> sendToSameRackMember()), 
      0, sameRackGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    scheduledExecutorService.scheduleAtFixedRate(() -> 
      threadService.execute(() -> sendToSameRackMemberPerNode()), 
      0, sameRackGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    scheduledExecutorService.scheduleAtFixedRate(() -> 
      threadService.execute(() -> sendToSameRackShared()), 
      0, sameRackGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    //same dc different rack 同数据中心，不同机房或机架
    scheduledExecutorService.scheduleAtFixedRate(() -> 
      threadService.execute(() -> sameDcDiffernetRackMember()), 
      0, sameDcGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    scheduledExecutorService.scheduleAtFixedRate(() -> 
    threadService.execute(() -> sameDcDiffernetRackPerNode()), 
    0, sameDcGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    scheduledExecutorService.scheduleAtFixedRate(() -> 
    threadService.execute(() -> sameDcDiffernetRackShared()), 
    0, sameDcGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    //different dc 不同的数据中心
    scheduledExecutorService.scheduleAtFixedRate(() -> 
      threadService.execute(() -> differentDcMember()), 
      0, differentDatacenterGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    scheduledExecutorService.scheduleAtFixedRate(() -> 
    threadService.execute(() -> differentDcPerNode()), 
    0, differentDatacenterGossipIntervalMs, TimeUnit.MILLISECONDS);
  
    scheduledExecutorService.scheduleAtFixedRate(() -> 
    threadService.execute(() -> differentDcShared()), 
    0, differentDatacenterGossipIntervalMs, TimeUnit.MILLISECONDS);
    
    //the dead 宕机成员
    scheduledExecutorService.scheduleAtFixedRate(() -> 
      threadService.execute(() -> sendToDeadMember()), 
      0, randomDeadMemberSendIntervalMs, TimeUnit.MILLISECONDS);
    
  }

  /**
   * 发送宕机成员
   */
  private void sendToDeadMember() {
    sendMembershipList(gossipManager.getMyself(), selectPartner(gossipManager.getDeadMembers()));
  }
  
  private List<LocalMember> differentDataCenter(){
    String myDc = gossipManager.getMyself().getProperties().get(DATACENTER);
    String rack = gossipManager.getMyself().getProperties().get(RACK);
    if (myDc == null|| rack == null){
      return Collections.emptyList();
    }
    List<LocalMember> notMyDc = new ArrayList<LocalMember>(10);
    for (LocalMember i : gossipManager.getLiveMembers()){
      if (!myDc.equals(i.getProperties().get(DATACENTER))){
        notMyDc.add(i);
      }
    }
    return notMyDc;
  }

  /**
   * 同数据中心不同机房或机架
   * @return
   */
  private List<LocalMember> sameDatacenterDifferentRack(){
    String myDc = gossipManager.getMyself().getProperties().get(DATACENTER);
    String rack = gossipManager.getMyself().getProperties().get(RACK);
    if (myDc == null|| rack == null){
      return Collections.emptyList();
    }
    List<LocalMember> notMyDc = new ArrayList<LocalMember>(10);
    for (LocalMember i : gossipManager.getLiveMembers()){
      if (myDc.equals(i.getProperties().get(DATACENTER)) && !rack.equals(i.getProperties().get(RACK))){
        notMyDc.add(i);
      }
    }
    return notMyDc;
  }

  /**
   * 同机房成员
   * @return
   */
  private List<LocalMember> sameRackNodes(){
    String myDc = gossipManager.getMyself().getProperties().get(DATACENTER);
    String rack = gossipManager.getMyself().getProperties().get(RACK);
    if (myDc == null|| rack == null){
      return Collections.emptyList();
    }
    List<LocalMember> sameDcAndRack = new ArrayList<LocalMember>(10);
    for (LocalMember i : gossipManager.getLiveMembers()){
      if (myDc.equals(i.getProperties().get(DATACENTER))
              && rack.equals(i.getProperties().get(RACK))){
        sameDcAndRack.add(i);
      }
    }
    return sameDcAndRack;
  }

  /**
   * 发送同机房成员
   */
  private void sendToSameRackMember() {
    LocalMember i = selectPartner(sameRackNodes());
    sendMembershipList(gossipManager.getMyself(), i);
  }

  /**
   * 发送同机房成员数据
   */
  private void sendToSameRackMemberPerNode() {
    sendPerNodeData(gossipManager.getMyself(), selectPartner(sameRackNodes()));
  }

  /**
   * 发送同机房共享数据
   */
  private void sendToSameRackShared() {
    sendSharedData(gossipManager.getMyself(), selectPartner(sameRackNodes()));
  }

  /**
   *不同数据中心成员
   */
  private void differentDcMember() {
    sendMembershipList(gossipManager.getMyself(), selectPartner(differentDataCenter()));
  }

  /**
   * 不同数据中心成员数据
   */
  private void differentDcPerNode() {
    sendPerNodeData(gossipManager.getMyself(), selectPartner(differentDataCenter()));
  }

  /**
   *不同数据中心成员共享数据
   */
  private void differentDcShared() {
    sendSharedData(gossipManager.getMyself(), selectPartner(differentDataCenter()));
  }

  /**
   *同数据中心不同机房成员
   */
  private void sameDcDiffernetRackMember() {
    sendMembershipList(gossipManager.getMyself(), selectPartner(sameDatacenterDifferentRack()));
  }

  /**
   *同数据中心不同机房成员数据
   */
  private void sameDcDiffernetRackPerNode() {
    sendPerNodeData(gossipManager.getMyself(), selectPartner(sameDatacenterDifferentRack()));
  }

  /**
   *同数据中心不同机房成员共享数据
   */
  private void sameDcDiffernetRackShared() {
    sendSharedData(gossipManager.getMyself(), selectPartner(sameDatacenterDifferentRack()));
  }
  
  @Override
  public void shutdown() {
    super.shutdown();
    scheduledExecutorService.shutdown();
    try {
      scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      LOGGER.debug("Issue during shutdown", e);
    }
    sendShutdownMessage();
    threadService.shutdown();
    try {
      threadService.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      LOGGER.debug("Issue during shutdown", e);
    }
  }
  
  /**
   * sends an optimistic shutdown message to several clusters nodes
   */
  protected void sendShutdownMessage(){
    List<LocalMember> l = gossipManager.getLiveMembers();
    int sendTo = l.size() < 3 ? 1 : l.size() / 3;
    for (int i = 0; i < sendTo; i++) {
      threadService.execute(() -> sendShutdownMessage(gossipManager.getMyself(), selectPartner(l)));
    }
  }
}
