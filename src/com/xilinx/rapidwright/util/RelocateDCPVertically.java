/*
 *
 * Copyright (c) 2022 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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
package com.xilinx.rapidwright.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
// Need util.Pair to allow compiling outside IDE with only dependent on RapidWright
import com.xilinx.rapidwright.util.Pair;



/**
 * Fill some black boxes of a given design with a specific implementation.
 */
public class RelocateDCPVertically {


	/**
	 * Fill some black boxes of the given design with the given implementation.
	 *
	 * @param top         The design with black boxes to fill
	 * @param mod         The implementation to fill the black boxes
	 * @param cell_anchor The reference INT tile used as the handle to {@code mod} parameter
	 * @param toCells     The list of pairs of a black box cell to be filled and its reference INT tile.
	 *                    The x-coordinate of this INT tile must match that of the cell_anchor.
	 */
	public static boolean relocateCell(Design top, Module mod, String cell_anchor, List<Pair<String, String>> toCells) {
		System.out.println("\n\nRelocate " + mod.getName());

		EDIFNetlist netlist = top.getNetlist();
		netlist.migrateCellAndSubCells(mod.getNetlist().getTopCell());
		top.setAutoIOBuffers(false);

		Site frSite = mod.getAnchor().getSite();
		Tile frTile = frSite.getTile();
		// The cell_anchor is used by a user as an anchor to the cell.  It can differ from anchorTile.
		// Relocate the cell is equivalent to moving the cell so that the cell_anchor align with the specified INT tile.
		Tile tFrom = top.getDevice().getTile(cell_anchor);
		System.out.printf("\n     fr %12s                : anchor %14s  %14s\n", cell_anchor, frSite, frTile);

		for (Pair<String, String> cell : toCells) {
			Tile tTo = top.getDevice().getTile(cell.getSecond());
			if (tFrom.getColumn() != tTo.getColumn()) {
				System.out.println("Target location of " + cell.getFirst() + ", " + cell.getSecond() +
						", is not vertically aligned with that of the implementation, " + cell_anchor + ".");
				return false;
			}
		}

		for (Pair<String, String> cell : toCells) {
			Tile tTo = top.getDevice().getTile(cell.getSecond());
			int verticalMoveOffset = tFrom.getRow() - tTo.getRow();

			Tile toTile = frTile.getTileNeighbor(0, verticalMoveOffset);
			Site toSite = toTile.getSites()[frTile.getSiteIndex(frSite)];
			System.out.printf("     to %12s  y_offset %4d : anchor %14s  %14s\n", cell.getSecond(), verticalMoveOffset, toSite, toTile);
			clearTargetSiteInsts(mod, toSite, top);
			if (!mod.isValidPlacement(toSite, top)) {
				System.out.println("Invalid placement.");
				return false;
			}

			ModuleInst mi = top.createModuleInst(cell.getFirst(), mod, true);
			mi.getCellInst().setCellType(mod.getNetlist().getTopCell());
			mi.place(toSite);
		}
		System.out.println("\n");
		return true;
	}


	/**
	 * Get the corresponding Site of the given SiteInst if the module is relocated.
	 *
	 * @param mod        The module to relocate
	 * @param inst       The SiteInst to relocate
	 * @param anchorSite The target location of the module anchor.
	 * @return The corresponding site or null if none is available.
	 */
	private static Site getCorrespondingValidSite(Module mod, SiteInst inst, Site anchorSite) {
		Site site = inst.getSite();
		Tile newTile = mod.getCorrespondingTile(site.getTile(), anchorSite.getTile());
		if (newTile == null) {
			return null;
		}
		Site newSite = site.getCorrespondingSite(inst.getSiteTypeEnum(), newTile);
		if (newSite == null) {
			return null;
		}
		return newSite;
	}


	/**
	 * Unplace all cells placed at the proposed existing SiteInts.
	 *
	 * @param proposedAnchorSite The proposed new anchor site
	 * @param design             The design to operate on
	 * @return False if an invalid Tile or Site is encountered. True otherwise.
	 */
	private static boolean clearTargetSiteInsts(Module mod, Site proposedAnchorSite, Design design) {

		for (SiteInst inst : mod.getSiteInsts()) {
			if (Utils.isLockedSiteType(inst.getSiteTypeEnum())) {
				continue;
			}

			Site newSite = getCorrespondingValidSite(mod, inst, proposedAnchorSite);

			SiteInst si = design.getSiteInstFromSite(newSite);
			if (design != null && si != null) {
				design.removeSiteInst(si);
			}
		}

		return true;
	}


	/**
	 * Determine if the given net is a clock net.
	 *
	 * @param net The net to check
	 * @return True if the net is a clock net
	 */
	public static boolean isClockNet(Net net) {
		// TODO: rely on attribute instead of name
		for (SitePinInst sink : net.getSinkPins()) {
			if (sink.getName().contains("CLK")) return true;
		}
		return false;
	}


	/**
	 * Combine PIPs on the clock nets that were associated with various wires and set it on the top level.
	 * This is to avoid misintepretation in Vivado.
	 *
	 * @param top      The design with black boxes to fill
	 * @param cellName A black box name
	 */
	public static void combinePIPonClockNets(Design top, String cellName) {
		System.out.println("Combine PIPs on clock nets of " + cellName);

		List<String> clockNets = new ArrayList<>();

		for (EDIFPortInst p : top.getNetlist().getCellInstFromHierName(cellName).getPortInsts()) {
			String hierNetName_outside = top.getNetlist().getHierNetFromName(cellName + "/" + p.getInternalNet().getName()).getHierarchicalNetName();
			for (EDIFHierNet net : top.getNetlist().getNetAliases(top.getNetlist().getHierNetFromName(hierNetName_outside))) {
				String physNetName = net.getHierarchicalNetName();
				Net physNet = top.getNet(physNetName);

				if (physNet != null) {
					if (isClockNet(physNet)) {
						clockNets.add(hierNetName_outside);
						break;
					}
				}
			}
		}

		for (String netName : clockNets) {
			Set<PIP> pips = new HashSet<>();
			for (EDIFHierNet net : top.getNetlist().getNetAliases(top.getNetlist().getHierNetFromName(netName))) {
				String physNetName = net.getHierarchicalNetName();
				Net physNet = top.getNet(physNetName);
				if (physNet != null) {
					for (PIP p : physNet.getPIPs()) {
						pips.add(p);
					}
				}
			}

			for (EDIFHierNet net : top.getNetlist().getNetAliases(top.getNetlist().getHierNetFromName(netName))) {
				String physNetName = net.getHierarchicalNetName();
				Net physNet = top.getNet(physNetName);
				if (physNet != null) {
					physNet.setPIPs(new ArrayList<>(pips));
				}
			}

		}
	}


	/**
	 * If the property of the design exists, set it to the given value.
	 * @param top  The design to set its property
	 * @param prop The full property name to set
	 * @param val  The value to set to
	 */
	public static void setPropertyValueInLateXDC(Design top, String prop, String val) {
		ArrayList<String> xdcList = new ArrayList<String>(top.getXDCConstraints(ConstraintGroup.LATE));
		int lineNum = 0;
		for (; lineNum < xdcList.size(); lineNum++) {
			String line = xdcList.get(lineNum);
			if (line.contains(prop)) {
				String[] words = line.split("\\s+");
				int idx = Arrays.asList(words).indexOf(prop);
				if (++idx < words.length) {
					words[idx] = val;
					String newLine = String.join(" ", words);
					xdcList.set(lineNum, newLine);
					break;
				}
			}
		}

		if (lineNum < xdcList.size()) {
			top.setXDCConstraints(xdcList, ConstraintGroup.LATE);
			System.out.println("\nINFO: property " + prop + " is found for the top design. It will be set to false.");
		}
	}


	private static String trimExtension(String name) {
		int idx = name.lastIndexOf('.');
		if(idx >=0) {
			return name.substring(0, idx);
		} else {
			return name;
		}
	}


// Example arguments
/*
   -in    hwct
   -from  hwct_rp0           INT_X32Y0
   -to    hw_contract_rp2    INT_X32Y240
   -to    hw_contract_rp1    INT_X32Y120
   -to    hw_contract_rp0    INT_X32Y0
   -out   hw_contract_userp0
*/

/*   need to do RP_1 last when RP_1 is the source, otherwise nets of some BUFGCEs become unrouted!
   -in    openacap_shell_bb
   -from  AES128_inst_1_RP1     INT_X32Y120
   -to    openacap_shell_i/RP_2 INT_X32Y240
   -to    openacap_shell_i/RP_0 INT_X32Y0
   -to    openacap_shell_i/RP_1 INT_X32Y120
   -out   openacap_shell_aes128
 */

	public static void main(String[] args) {
		String usage = String.join(System.getProperty("line.separator"),
				"Relocate DCP to fill vertically aligned black boxes",
				"  -in   <name of the DCP of a design with black boxes>",
				"  -out  <name of the output DCP>",
				"  -form <name of the DCP of an implementation to fill the black boxes>",
				"        <a reference interconnect tile, eg., INT_X32Y0>",
				"  -to   <a full hierarchical name to a black box in the DCP specified by -in>",
				"        <a reference interconnect tile, eg., INT_X32Y120>",
				"  -to   <can be repeated as many as the number of black boxes to fill>",
				"",
				"Note: If the DCP specified by -form has a corresponding black box in the DCP specified by -in,",
				"      it can be filled as well and it must be listed as the last -to option.");


		long startTime = System.nanoTime();

		String topDCPName  = null;
		String newDCPName  = null;
		String cellDCPName = null;
		String cellAnchor  = null;
		List<Pair<String,String>> targets = new ArrayList<>();

		String toCell = null;
		String toLoc  = null;

		// Collect command line arguments
		int i = 0;
		while (i < args.length) {
			switch (args[i]) {
				case "-help":
					System.out.println(usage);
					System.exit(0);
					break;
				case "-in":   topDCPName = args[++i];
					break;
				case "-out":  newDCPName = args[++i];
					break;
				case "-from": cellDCPName = args[++i];
					if (i < args.length) {
						cellAnchor  = args[++i];
					} else {
						System.out.println("Missing value for option -from.");
						System.out.println(usage);
						System.exit(1);
					}
					break;
				case "-to":   toCell  = args[++i];
					if (i < args.length) {
						toLoc   = args[++i];
						targets.add(new Pair<>(toCell, toLoc));
					} else {
						System.out.println("Missing value for option -to");
						System.out.println(usage);
						System.exit(1);
					}
					break;
				default:      System.out.println("Invalid option " + args[i] + " found.");
					System.out.println(usage);
					System.exit(1);
					break;
			}
			i++;
		}

		// Report collected arguments
		System.out.println("RelocateDCPVertically");
		System.out.println("  -in   " + topDCPName);
		System.out.println("  -out  " + newDCPName);
		System.out.println("  -from " + cellDCPName + " " + cellAnchor);
		for (Pair<String,String> toCellLoc : targets) {
			System.out.println("  -to   " + toCellLoc.getFirst() + " " + toCellLoc.getSecond());
		}
		System.out.println();

		topDCPName  = trimExtension(topDCPName);
		newDCPName  = trimExtension(newDCPName);
		cellDCPName = trimExtension(cellDCPName);


		// Fill the black boxes
		Design top = Design.readCheckpoint(topDCPName + ".dcp", topDCPName + ".edf");
		Module mod = new Module(Design.readCheckpoint(cellDCPName + ".dcp", cellDCPName + ".edf"), false);

		if (relocateCell(top, mod, cellAnchor, targets)) {

			top.getNetlist().resetParentNetMap();

			for (Pair<String,String> toCellLoc : targets) {
				combinePIPonClockNets(top, toCellLoc.getFirst());
			}

			setPropertyValueInLateXDC (top, "HD.RECONFIGURABLE", "false");

			System.out.println("\n");
			top.writeCheckpoint(newDCPName + ".dcp");
			System.out.println("\n\nFill " + targets.size() + " target black boxes successfully.\n");

		} else {
			System.out.println("\n\nFail to fill all target black boxes.\n");
		}

		long stopTime = System.nanoTime();
		System.out.printf("\nElapsed time %3.0f sec.\n\n", (stopTime - startTime)*1e-9);
	}
}
