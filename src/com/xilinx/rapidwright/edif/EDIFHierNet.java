/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
/**
 *
 */
package com.xilinx.rapidwright.edif;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.jetbrains.annotations.NotNull;

/**
 * Combines an {@link EDIFNet} with a full hierarchical
 * instance name to uniquely identify a net in a netlist.
 *
 * Created on: Sep 13, 2017
 */
public class EDIFHierNet {

    @NotNull
    private final EDIFHierCellInst hierarchicalInst;
    @NotNull
    private final EDIFNet net;

    /**
     * Constructor
     * @param hierarchicalInst Parent instance cell that contains this net
     * @param net The actual net object
     */
    public EDIFHierNet(@NotNull EDIFHierCellInst hierarchicalInst, @NotNull EDIFNet net) {
        this.hierarchicalInst = Objects.requireNonNull(hierarchicalInst);
        this.net = Objects.requireNonNull(net);
    }

    /**
     * @return the hierarchicalInstName
     */
    public String getHierarchicalInstName() {
        return hierarchicalInst.getFullHierarchicalInstName();
    }

    /**
     * @return the net
     */
    public EDIFNet getNet() {
        return net;
    }

    /**
     * Given a port on the net, gives the full hierarchical name of the instance
     * attached to the port.
     * @param port The reference port of the instance.
     * @return Full hierarchical name of the instance attached to the port.
     */
    public String getHierarchicalInstName(EDIFPortInst port) {
        StringBuilder sb = new StringBuilder();
        if (hierarchicalInst.enterHierarchicalName(sb)) {
            sb.append(EDIFTools.EDIF_HIER_SEP);
        }
        sb.append(port.getCellInst().getName());
        return sb.toString();
    }

    public String getHierarchicalNetName() {
        StringBuilder sb = new StringBuilder();
        if (hierarchicalInst.enterHierarchicalName(sb)) {
            sb.append(EDIFTools.EDIF_HIER_SEP);
        }
        sb.append(net.getName());
        return sb.toString();
    }

    /**
     * Gets the parent cell instance where this net is defined.
     * @return The parent cell instance of this net.
     */
    public EDIFCellInst getParentInst() {
        return hierarchicalInst.getInst();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        //TODO does it make sense for hierarchicalInst or net to be null?
        result = prime * result + ((hierarchicalInst == null) ? 0 : hierarchicalInst.hashCode());
        result = prime * result + ((net == null) ? 0 : net.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        EDIFHierNet other = (EDIFHierNet) obj;
        if (hierarchicalInst == null) {
            if (other.hierarchicalInst != null)
                return false;
        } else if (!hierarchicalInst.equals(other.hierarchicalInst))
            return false;
        if (net == null) {
            if (other.net != null)
                return false;
        } else if (!net.equals(other.net))
            return false;
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return getHierarchicalNetName();
    }


    public EDIFHierCellInst getHierarchicalInst() {
        return hierarchicalInst;
    }

    /**
     * Gets all connected leaf port instances on this hierarchical net and its aliases.
     * @return The list of all leaf cell port instances connected to this hierarchical net and its
     * aliases.
     */
    public List<EDIFHierPortInst> getLeafHierPortInsts() {
        return getLeafHierPortInsts(true);
    }

    /**
     * Gets all connected leaf port instances on this hierarchical net and its aliases.
     * @param includeSourcePins A flag to include source pins in the result.  Setting this to false
     * only returns the sinks.
     * @return The list of all leaf cell port instances connected to this hierarchical net and its
     * aliases.
     */
    public List<EDIFHierPortInst> getLeafHierPortInsts(boolean includeSourcePins) {
        List<EDIFHierPortInst> leafCellPins = new ArrayList<>();
        Queue<EDIFHierNet> queue = new ArrayDeque<>();
        queue.add(this);
        HashSet<EDIFHierNet> visited = new HashSet<>();

        EDIFHierNet parentNet = null;
        while (!queue.isEmpty()) {
            EDIFHierNet net = queue.poll();
            if (!visited.add(net)) {
                continue;
            }
            for (EDIFPortInst relP : net.getNet().getPortInsts()) {
                EDIFHierPortInst p = new EDIFHierPortInst(net.getHierarchicalInst(), relP);

                boolean isCellPin = relP.getCellInst() != null && relP.getCellInst().getCellType().isLeafCellOrBlackBox();
                if (isCellPin) {
                    if (p.isInput() || (includeSourcePins && p.isOutput())) {
                        leafCellPins.add(p);
                    }
                }

                boolean isToplevelInput = p.getHierarchicalInst().isTopLevelInst() && relP.getCellInst() == null && p.isInput();
                if (isToplevelInput || (isCellPin && p.isOutput())) {
                    if (parentNet != null) {
                        throw new RuntimeException("Multiple sources!");
                    }
                    parentNet = net;
                }

                if (p.getPortInst().getCellInst() == null) {
                    // Moving up in hierarchy
                    if (!p.getHierarchicalInst().isTopLevelInst()) {
                        final EDIFHierPortInst upPort = p.getPortInParent();
                        if (upPort != null) {
                            queue.add(upPort.getHierarchicalNet());
                        }
                    }
                } else {
                    // Moving down in hierarchy
                    EDIFHierNet otherNet = p.getInternalNet();
                    if (otherNet == null) {
                        // Looks unconnected
                        continue;
                    }
                    queue.add(otherNet);
                }
            }
        }

        return leafCellPins;
    }
}
