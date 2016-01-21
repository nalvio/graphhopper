/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.Weighting;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.shapes.BBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This class manages all storage related methods and delegates the calls to the associated graphs. The associated graphs manage their own necessary data structures and are used to provide e.g. different traversal methods. By default this
 * class implements the graph interface and results in identical behavior as the Graph instance from getGraph(Graph.class)
 * <p>
 *
 * @author Peter Karich
 * @see GraphBuilder to create a (CH)Graph easier
 * @see #getGraph(java.lang.Class)
 */
public final class GraphHopperStorage implements GraphStorage, Graph {
	private final Directory dir;
	private final StorableProperties properties;
	private final BaseGraph baseGraph;
	// same flush order etc
	private final Collection<CHGraphImpl> chGraphs = new ArrayList<CHGraphImpl>(5);
	private EncodingManager encodingManager;

	public GraphHopperStorage(Directory dir, final EncodingManager encodingManager, boolean withElevation, GraphExtension extendedStorage) {
		if(extendedStorage == null) {
			throw new IllegalArgumentException("GraphExtension cannot be null, use NoOpExtension");
		}

		this.encodingManager = encodingManager;
		this.dir = dir;
		this.properties = new StorableProperties(dir);
		InternalGraphEventListener listener = new InternalGraphEventListener() {
			@Override
			public void initStorage() {
				for(CHGraphImpl cg : chGraphs) {
					cg.initStorage();
				}
			}

			@Override
			public void freeze() {
				for(CHGraphImpl cg : chGraphs) {
					cg._freeze();
				}
			}
		};

		this.baseGraph = new BaseGraph(dir, encodingManager, withElevation, listener, extendedStorage);
	}

	public boolean addWeighting(Weighting w) {
		CHGraphImpl chGraph = new CHGraphImpl(w, dir, this.baseGraph);
		chGraphs.add(chGraph);
		return chGraph.loadExisting();
	}

	/**
	 * This method returns the routing graph for the specified weighting, could be potentially filled with shortcuts.
	 */
	public <T extends Graph> T getGraph(Class<T> clazz, Weighting weighting) {
		if(clazz.equals(Graph.class)) {
			return (T) baseGraph;
		}

		if(chGraphs.isEmpty()) {
			throw new IllegalStateException("Cannot find graph implementation for " + clazz);
		}

		if(weighting == null) {
			throw new IllegalStateException("Cannot find CHGraph with null weighting");
		}

		List<Weighting> existing = new ArrayList<Weighting>();
		for(CHGraphImpl cg : chGraphs) {
			if(cg.getWeighting() == weighting) {
				return (T) cg;
			}

			existing.add(cg.getWeighting());
		}

		throw new IllegalStateException("Cannot find CHGraph for specified weighting: " + weighting + ", existing:" + existing);
	}

	public <T extends Graph> T getGraph(Class<T> clazz) {
		if(clazz.equals(Graph.class)) {
			return (T) baseGraph;
		}

		if(chGraphs.isEmpty()) {
			throw new IllegalStateException("Cannot find graph implementation for " + clazz);
		}

		CHGraph cg = chGraphs.iterator().next();
		return (T) cg;
	}

	public boolean isCHPossible() {
		return !chGraphs.isEmpty();
	}

	public List<Weighting> getCHWeightings() {
		List<Weighting> list = new ArrayList<Weighting>(chGraphs.size());
		for(CHGraphImpl cg : chGraphs) {
			list.add(cg.getWeighting());
		}
		return list;
	}

	/**
	 * @return the directory where this graph is stored.
	 */
	@Override
	public Directory getDirectory() {
		return dir;
	}

	@Override
	public void setSegmentSize(int bytes) {
		baseGraph.setSegmentSize(bytes);

		for(CHGraphImpl cg : chGraphs) {
			cg.setSegmentSize(bytes);
		}
	}

	/**
	 * After configuring this storage you need to create it explicitly.
	 */
	@Override
	public GraphHopperStorage create(long byteCount) {
		baseGraph.checkInit();
		if(encodingManager == null) {
			throw new IllegalStateException("EncodingManager can only be null if you call loadExisting");
		}

		long initSize = Math.max(byteCount, 100);
		properties.create(100);

		properties.put("graph.bytesForFlags", encodingManager.getBytesForFlags());
		properties.put("graph.flagEncoders", encodingManager.toDetailsString());

		properties.put("graph.byteOrder", dir.getByteOrder());
		properties.put("graph.dimension", baseGraph.nodeAccess.getDimension());
		properties.putCurrentVersions();

		baseGraph.create(initSize);

		for(CHGraphImpl cg : chGraphs) {
			cg.create(byteCount);
		}

		properties.put("graph.chWeightings", getCHWeightings().toString());
		return this;
	}

	@Override
	public EncodingManager getEncodingManager() {
		return encodingManager;
	}

	@Override
	public StorableProperties getProperties() {
		return properties;
	}

	public void setAdditionalEdgeField(long edgePointer, int value) {
		baseGraph.setAdditionalEdgeField(edgePointer, value);
	}

	@Override
	public void markNodeRemoved(int index) {
		baseGraph.getRemovedNodes().add(index);
	}

	@Override
	public boolean isNodeRemoved(int index) {
		return baseGraph.getRemovedNodes().contains(index);
	}

	@Override
	public void optimize() {
		if(isFrozen()) {
			throw new IllegalStateException("do not optimize after graph was frozen");
		}

		int delNodes = baseGraph.getRemovedNodes().getCardinality();
		if(delNodes <= 0) {
			return;
		}

		// Deletes only nodes.
		// It reduces the fragmentation of the node space but introduces new unused edges.
		baseGraph.inPlaceNodeRemove(delNodes);

		// Reduce memory usage
		baseGraph.trimToSize();
	}

	@Override
	public boolean loadExisting() {
		baseGraph.checkInit();
		if(properties.loadExisting()) {
			properties.checkVersions(false);
			String byteOrder = properties.get("graph.byteOrder");
			if(!byteOrder.equalsIgnoreCase("" + dir.getByteOrder())) {
				throw new IllegalStateException("Configured byteOrder (" + byteOrder + ") is not equal to byteOrder of loaded graph (" + dir.getByteOrder() + ")");
			}

			String dim = properties.get("graph.dimension");
			baseGraph.loadExisting(dim);
			return true;
		}
		return false;
	}

	@Override
	public void flush() {
		for(CHGraphImpl cg : chGraphs) {
			cg.setEdgesHeader();
			cg.flush();
		}

		baseGraph.flush();
		properties.flush();
	}

	@Override
	public void close() {
		properties.close();
		baseGraph.close();

		for(CHGraphImpl cg : chGraphs) {
			cg.close();
		}
	}

	@Override
	public boolean isClosed() {
		return baseGraph.nodes.isClosed();
	}

	@Override
	public long getCapacity() {
		long cnt = baseGraph.getCapacity() + properties.getCapacity();

		for(CHGraphImpl cg : chGraphs) {
			cnt += cg.getCapacity();
		}
		return cnt;
	}

	/**
	 * Avoid that edges and nodes of the base graph are further modified. Necessary as hook for e.g. ch graphs on top to initilize themself
	 */
	public void freeze() {
		if(!baseGraph.isFrozen()) {
			baseGraph.freeze();
		}
	}

	boolean isFrozen() {
		return baseGraph.isFrozen();
	}

	@Override
	public String toDetailsString() {
		String str = baseGraph.toDetailsString();
		for(CHGraphImpl cg : chGraphs) {
			str += ", " + cg.toDetailsString();
		}

		return str;
	}

	@Override
	public String toString() {
		return (isCHPossible() ? "CH|" : "")
				+ encodingManager
				+ "|" + getDirectory().getDefaultType()
				+ "|" + baseGraph.nodeAccess.getDimension() + "D"
				+ "|" + baseGraph.extStorage
				+ "|" + getProperties().versionsToString();
	}

	// now all delegation graph method to avoid ugly programming flow ala
	// GraphHopperStorage storage = ..;
	// Graph g = storage.getGraph(Graph.class);
	// instead directly the storage can be used to traverse the base graph
	@Override
	public Graph getBaseGraph() {
		return baseGraph;
	}

	@Override
	public final int getNodes() {
		return baseGraph.getNodes();
	}

	@Override
	public final NodeAccess getNodeAccess() {
		return baseGraph.getNodeAccess();
	}

	@Override
	public final BBox getBounds() {
		return baseGraph.getBounds();
	}

	@Override
	public final EdgeIteratorState edge(int a, int b) {
		return baseGraph.edge(a, b);
	}

	@Override
	public final EdgeIteratorState edge(int a, int b, double distance, boolean bothDirections) {
		return baseGraph.edge(a, b, distance, bothDirections);
	}

	@Override
	public final EdgeIteratorState getEdgeIteratorState(int edgeId, int adjNode) {
		return baseGraph.getEdgeIteratorState(edgeId, adjNode);
	}

	@Override
	public final AllEdgesIterator getAllEdges() {
		return baseGraph.getAllEdges();
	}

	@Override
	public final EdgeExplorer createEdgeExplorer(EdgeFilter filter) {
		return baseGraph.createEdgeExplorer(filter);
	}

	@Override
	public final EdgeExplorer createEdgeExplorer() {
		return baseGraph.createEdgeExplorer();
	}

	@Override
	public final Graph copyTo(Graph g) {
		return baseGraph.copyTo(g);
	}

	@Override
	public final GraphExtension getExtension() {
		return baseGraph.getExtension();
	}
}
