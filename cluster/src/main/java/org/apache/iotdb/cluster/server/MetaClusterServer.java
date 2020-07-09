/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.cluster.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.rpc.thrift.AddNodeResponse;
import org.apache.iotdb.cluster.rpc.thrift.AppendEntriesRequest;
import org.apache.iotdb.cluster.rpc.thrift.AppendEntryRequest;
import org.apache.iotdb.cluster.rpc.thrift.CheckStatusResponse;
import org.apache.iotdb.cluster.rpc.thrift.ElectionRequest;
import org.apache.iotdb.cluster.rpc.thrift.ExecutNonQueryReq;
import org.apache.iotdb.cluster.rpc.thrift.HeartBeatRequest;
import org.apache.iotdb.cluster.rpc.thrift.HeartBeatResponse;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.SendSnapshotRequest;
import org.apache.iotdb.cluster.rpc.thrift.StartUpStatus;
import org.apache.iotdb.cluster.rpc.thrift.TNodeStatus;
import org.apache.iotdb.cluster.rpc.thrift.TSMetaService;
import org.apache.iotdb.cluster.rpc.thrift.TSMetaService.AsyncProcessor;
import org.apache.iotdb.cluster.rpc.thrift.TSMetaService.Processor;
import org.apache.iotdb.cluster.server.member.MetaGroupMember;
import org.apache.iotdb.cluster.server.service.MetaAsyncService;
import org.apache.iotdb.cluster.server.service.MetaSyncService;
import org.apache.iotdb.cluster.utils.nodetool.ClusterMonitor;
import org.apache.iotdb.db.exception.StartupException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.service.RegisterManager;
import org.apache.iotdb.service.rpc.thrift.TSStatus;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * MetaCluster manages the whole cluster's metadata, such as what nodes are in the cluster and the
 * data partition. Each node has one MetaClusterServer instance, the single-node IoTDB instance is
 * started-up at the same time.
 */
public class MetaClusterServer extends RaftServer implements TSMetaService.AsyncIface,
    TSMetaService.Iface {

  // each node only contains one MetaGroupMember
  private MetaGroupMember member;
  private IoTDB ioTDB;
  // to register the ClusterMonitor that helps monitoring the cluster
  private RegisterManager registerManager = new RegisterManager();
  private MetaAsyncService asyncService;
  private MetaSyncService syncService;

  public MetaClusterServer() throws QueryProcessException {
    super();
    member = new MetaGroupMember(protocolFactory, thisNode);
    asyncService = new MetaAsyncService(member);
    syncService = new MetaSyncService(member);
  }

  /**
   * Besides the standard RaftServer start-up, the IoTDB instance, a MetaGroupMember and the
   * ClusterMonitor are also started.
   *
   * @throws TTransportException
   * @throws StartupException
   */
  @Override
  public void start() throws TTransportException, StartupException {
    super.start();
    ioTDB = new IoTDB();
    ioTDB.active();
    member.start();
    registerManager.register(ClusterMonitor.INSTANCE);
  }

  /**
   * Also stops the IoTDB instance, the MetaGroupMember and the ClusterMonitor.
   */
  @Override
  public void stop() {
    super.stop();
    ioTDB.stop();
    ioTDB = null;
    member.stop();
    registerManager.deregisterAll();
  }

  /**
   * Build a initial cluster with other nodes on the seed list.
   */
  public void buildCluster() {
    member.buildCluster();
  }

  /**
   * Pick up a node from the seed list and send a join request to it.
   *
   * @return whether the node has joined the cluster.
   */
  public boolean joinCluster() {
    return member.joinCluster();
  }

  /**
   * MetaClusterServer uses the meta port to create the socket.
   *
   * @return
   * @throws TTransportException
   */
  @Override
  TServerTransport getServerSocket() throws TTransportException {
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      return new TNonblockingServerSocket(new InetSocketAddress(config.getLocalIP(),
          config.getLocalMetaPort()), getConnectionTimeoutInMS());
    } else {
      return new TServerSocket(new InetSocketAddress(config.getLocalIP(),
          config.getLocalMetaPort()));
    }
  }

  @Override
  String getClientThreadPrefix() {
    return "MetaClientThread-";
  }

  @Override
  String getServerClientName() {
    return "MetaServerThread-";
  }

  @Override
  TProcessor getProcessor() {
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      return new AsyncProcessor(this);
    } else {
      return new Processor<>(this);
    }
  }

  // Request forwarding. There is only one MetaGroupMember each node, so all requests will be
  // directly sent to that member. See the methods in MetaGroupMember for details

  @Override
  public void addNode(Node node, StartUpStatus startUpStatus, AsyncMethodCallback resultHandler) {
    asyncService.addNode(node, startUpStatus, resultHandler);
  }

  @Override
  public void sendHeartbeat(HeartBeatRequest request, AsyncMethodCallback resultHandler) {
    asyncService.sendHeartbeat(request, resultHandler);
  }

  @Override
  public void startElection(ElectionRequest electionRequest, AsyncMethodCallback resultHandler) {
    asyncService.startElection(electionRequest, resultHandler);
  }

  @Override
  public void appendEntries(AppendEntriesRequest request, AsyncMethodCallback resultHandler) {
    asyncService.appendEntries(request, resultHandler);
  }

  @Override
  public void appendEntry(AppendEntryRequest request, AsyncMethodCallback resultHandler) {
    asyncService.appendEntry(request, resultHandler);
  }

  @Override
  public void sendSnapshot(SendSnapshotRequest request, AsyncMethodCallback resultHandler) {
    asyncService.sendSnapshot(request, resultHandler);
  }

  @Override
  public void executeNonQueryPlan(ExecutNonQueryReq request,
      AsyncMethodCallback<TSStatus> resultHandler) {
    asyncService.executeNonQueryPlan(request, resultHandler);
  }

  @Override
  public void requestCommitIndex(Node header, AsyncMethodCallback<Long> resultHandler) {
    asyncService.requestCommitIndex(header, resultHandler);
  }

  @Override
  public void checkAlive(AsyncMethodCallback<Node> resultHandler) {
    asyncService.checkAlive(resultHandler);
  }

  @Override
  public void readFile(String filePath, long offset, int length,
      AsyncMethodCallback<ByteBuffer> resultHandler) {
    asyncService.readFile(filePath, offset, length, resultHandler);
  }

  @Override
  public void queryNodeStatus(AsyncMethodCallback<TNodeStatus> resultHandler) {
    asyncService.queryNodeStatus(resultHandler);
  }

  public MetaGroupMember getMember() {
    return member;
  }

  @Override
  public void checkStatus(StartUpStatus startUpStatus,
      AsyncMethodCallback<CheckStatusResponse> resultHandler) {
    asyncService.checkStatus(startUpStatus, resultHandler);
  }

  @Override
  public void removeNode(Node node, AsyncMethodCallback<Long> resultHandler) {
    asyncService.removeNode(node, resultHandler);
  }

  @Override
  public void exile(AsyncMethodCallback<Void> resultHandler) {
    asyncService.exile(resultHandler);
  }

  @Override
  public void matchTerm(long index, long term, Node header,
      AsyncMethodCallback<Boolean> resultHandler) {
    asyncService.matchTerm(index, term, header, resultHandler);
  }

  @Override
  public AddNodeResponse addNode(Node node, StartUpStatus startUpStatus) throws TException {
    return syncService.addNode(node, startUpStatus);
  }

  @Override
  public CheckStatusResponse checkStatus(StartUpStatus startUpStatus) {
    return syncService.checkStatus(startUpStatus);
  }

  @Override
  public long removeNode(Node node) throws TException {
    return syncService.removeNode(node);
  }

  @Override
  public void exile() {
    syncService.exile();
  }

  @Override
  public TNodeStatus queryNodeStatus() {
    return syncService.queryNodeStatus();
  }

  @Override
  public Node checkAlive() {
    return syncService.checkAlive();
  }

  @Override
  public HeartBeatResponse sendHeartbeat(HeartBeatRequest request) {
    return syncService.sendHeartbeat(request);
  }

  @Override
  public long startElection(ElectionRequest request) {
    return syncService.startElection(request);
  }

  @Override
  public long appendEntries(AppendEntriesRequest request) throws TException {
    return syncService.appendEntries(request);
  }

  @Override
  public long appendEntry(AppendEntryRequest request) throws TException {
    return syncService.appendEntry(request);
  }

  @Override
  public void sendSnapshot(SendSnapshotRequest request) throws TException {
    syncService.sendSnapshot(request);
  }

  @Override
  public TSStatus executeNonQueryPlan(ExecutNonQueryReq request) throws TException {
    return syncService.executeNonQueryPlan(request);
  }

  @Override
  public long requestCommitIndex(Node header) throws TException {
    return syncService.requestCommitIndex(header);
  }

  @Override
  public ByteBuffer readFile(String filePath, long offset, int length) throws TException {
    return syncService.readFile(filePath, offset, length);
  }

  @Override
  public boolean matchTerm(long index, long term, Node header) {
    return syncService.matchTerm(index, term, header);
  }
}
