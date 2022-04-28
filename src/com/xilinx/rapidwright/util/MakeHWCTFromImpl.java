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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public class MakeHWCTFromImpl {

    public static void main(String[] args) {
        String usage = String.join(System.getProperty("line.separator"),
                "Make a implemented Hardware Contract (HWCT) by copying an implementation into a target design.",
                "  -in       <name of the routed DCP to make HWCT of>",
                "  -formCell <name of the cell in the DCP specified by -in to copy the implementation from>",
                "            <a reference interconnect tile, eg., INT_X32Y0>",
                "  -to       <a full hierarchical name to a black box in the DCP specified by -into>",
                "            <a reference interconnect tile, eg., INT_X32Y120>",
                "  -to       <can be repeated as many as the number of black boxes to fill>",
                "  -out      <name of the output DCP with some black boxes filled as specified>",
                "  Notes: ",
                "  1) For UltraScale+ and older devices, all reference tiles should be vertically align to have a valid implementation.",
                "     Versal devices allows for some horizontal moves. But, there is no validity check.",
                "  2) The black box for -to must be a direct child of hw_contract cell.");

/*
        -in        post_route.dcp
        -fromCell  video_cp_i/composable/dfx_decouplers/hw_contract/hw_contract_pr1  INT_X0Y60
        -into      hwct.dcp
        -to        hw_contract_pr0 INT_X0Y120
        -to        hw_contract_pr1 INT_X0Y60
        -to        hw_contract_pr2 INT_X0Y0
        -out       hwctdirect2.dcp

        -in
post_route_apr23.dcp
-fromCell
hw_contract_pr0
INT_X0Y120
-to
hw_contract_pr1
INT_X0Y60
-to
hw_contract_pr2
INT_X0Y0
-out
 */

        ArrayList<Pair<String, String>> targets = new ArrayList<>();
        String srcDCPName = "";
        String srcCellName = "";
        String cellAnchor = "";
        String outDCPName = "";


        // Collect command line arguments
        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "-help":
                    System.out.println(usage);
                    System.exit(0);
                    break;
                case "-in":
                    srcDCPName = args[++i];
                    break;
                case "-out":
                    outDCPName = args[++i];
                    break;
                case "-fromCell":
                    srcCellName = args[++i];
                    if (i < args.length) {
                        cellAnchor = args[++i];
                    } else {
                        System.out.println("Missing value for option -from.");
                        System.out.println(usage);
                        System.exit(1);
                    }
                    break;
                case "-to":
                    String toCell = args[++i];
                    if (i < args.length) {
                        String toLoc = args[++i];
                        targets.add(new Pair<>(toCell, toLoc));
                    } else {
                        System.out.println("Missing value for option -to");
                        System.out.println(usage);
                        System.exit(1);
                    }
                    break;
                default:
                    System.out.println("Invalid option " + args[i] + " found.");
                    System.out.println(usage);
                    System.exit(1);
                    break;
            }
            i++;
        }


        // Report collected arguments
        System.out.println("MakeHWCTFromImpl");
        System.out.println("  -in       " + srcDCPName);
        System.out.println("  -fromCell " + srcCellName + " " + cellAnchor);
        for (Pair<String, String> toCellLoc : targets) {
            System.out.println("  -to       " + toCellLoc.getFirst() + " " + toCellLoc.getSecond());
        }
        System.out.println("  -out      " + outDCPName);
        System.out.println();


        Design srcDesign = Design.readCheckpoint(srcDCPName);

        // ****IMPORTANT****  this must be before extracting hwct, otherwise crash in copyImplementation
        List<EDIFHierCellInst> srcCell = srcDesign.getNetlist().findCellInsts("*"+ srcCellName);
        if (srcCell.isEmpty()) {
            System.out.println("ERROR: Cannot find a cell name " + srcCellName + ", specified by -fromCell, at any hierarchy level.");
            System.exit(1);
        }
        Design d2 = DesignTools.createDesignFromCellWithStatic(srcDesign, srcCell.get(0).getFullHierarchicalInstName());

        List<EDIFHierCellInst> ci = srcDesign.getNetlist().findCellInsts("*hw_contract");
        if (ci.isEmpty()) {
            System.out.println("ERROR: Cannot find a cell name hw_contract at any hierarchy level.");
            System.exit(1);
        }

        Design hwct = DesignTools.createDesignFromCellWithoutStatic(srcDesign, ci.get(0).getFullHierarchicalInstName());
        // The source is also a target in another design
        targets.add(new Pair<>(srcCellName, cellAnchor));
        for (Pair<String, String> cell : targets) {
            DesignTools.makeBlackBox(hwct,cell.getFirst());
        }


        // Use d2 directly without write/readCheckpoint will cause Vivado to crash later
        d2.writeCheckpoint("d2_temp.dcp");
        Design hwct_component = Design.readCheckpoint("d2_temp.dcp");
        try {
            Files.deleteIfExists(Paths.get("d2_temp.dcp"));
        } catch (NoSuchFileException e) {
            System.out.println("No such file/directory exists");
        } catch (IOException e) {
            System.out.println("Invalid permissions.");
        }



        Module mod = new Module(hwct_component, false);
        if (RelocateModulesIntoBlackboxes.relocateModuleInsts(hwct, mod, cellAnchor, targets)) {

            RelocateModulesIntoBlackboxes.postProcessing(hwct, targets);

            System.out.println("\n");
            hwct.writeCheckpoint(outDCPName );
            System.out.println("\n\nCreate HWCT successfully.\n");

        } else {
            System.out.println("\n\nFailed to create HWCT. Some black boxes cannnot be filled.\n");
        }
    }
}