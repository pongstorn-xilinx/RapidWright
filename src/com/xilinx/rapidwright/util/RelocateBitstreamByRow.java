/*
 *
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
 *
 */

package com.xilinx.rapidwright.util;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import com.xilinx.rapidwright.bitstream.Bitstream;
import com.xilinx.rapidwright.bitstream.CRC;
import com.xilinx.rapidwright.bitstream.FAR;
import com.xilinx.rapidwright.bitstream.OpCode;
import com.xilinx.rapidwright.bitstream.Packet;
import com.xilinx.rapidwright.bitstream.RegisterType;
import com.xilinx.rapidwright.device.Series;


/**
 * Relocate a partial bitstream by the specified number of rows for an UltraScale/UltraScale+ device.
 *
 * The following conditions are necessary for the resulting bitstream to work on the board.
 *  1) Both the source and target are DFX regions and their routing footprints are of the same size.
 *  2) The interfaces to both regions are physically the same.
 *  3) The interfaces to both regions must use the same memory address or the different bits are don't care bits in the region.
 *  4) The interfaces between these regions and the static region use different clocks with proper CDC circuit.
 *  5) The clocks used in the static region must not go into these regions.
 *  6) The clocks route from static to these regions must be uniform across the two regions, either through HROUTE or HDISTR.
 *     The clock track used must also be the same among the regions.
 *  7) The clocks in each region must driven by BUFG in the region or driven from the static region.
 *     In the later case, only one clock route per clock is allowed to each region and Condition 6 must be met.
 */
public class RelocateBitstreamByRow {

    /**
     * Format int array into 32-bit hex, eg., return 000cd901 for 841985
     *
     * @param data int
     * @return hex string.
     */
    static private String toHexString(int[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            sb.append(String.format("%08x ", data[i]));
        }
        return sb.toString();
    }


    /**
     * Update multiple CRC within a partial bitstream.
     * There are two patterns: one that reset CRC computation and one that is not.
     * @param target    Bitstream to update CRC for
     * @param verbose   Print out information
     */
    public static void updateCRC (Bitstream target, boolean verbose) {
        if (verbose) {System.out.println("\nbegin updateCRC");}
        // Separating CRC computing out to simplify the code. Can combine with the two loopsreplaceContent if needed.
        // TODO: update the packets itself to reduce memory usage.
        List<Packet> newPackets = new ArrayList<>();
        CRC CRCComputer = new CRC();
        boolean previousCRCWrite = true;
        for (Packet packet : target.getPackets()) {
            if (packet.isTypeOnePacket() && (packet.getRegister() == RegisterType.CRC) && (packet.getOpCode() == OpCode.WRITE)) {
                //write to CRC is not included.
                previousCRCWrite = true;
                if (verbose) {System.out.println("  CRC " + toHexString(new int[]{ CRCComputer.getCRCValue() }));}
                newPackets.add(new Packet(packet.getHeader(), CRCComputer.getCRCValue()));
            } else if (previousCRCWrite && packet.isTypeOnePacket() && (packet.getRegister() == RegisterType.CRC) && (packet.getOpCode() == OpCode.NOP)) {
                // if CRC NOP is right after CRC WRITE, reset the CRC
                previousCRCWrite = false;
                CRCComputer.reset();
                CRCComputer.updateCRC(packet);
                newPackets.add(packet);
            } else {
                previousCRCWrite = false;
                CRCComputer.updateCRC(packet);
                newPackets.add(packet);
            }
        }

        target.setPackets(newPackets);
        if (verbose) {System.out.println("end updateCRC");}
    }


    /**
     * Relocate the given partial bitstream by the given number of clock region rows.
     * @param bitstream Bitstream to update its rows
     * @param series    The device series
     * @param rowOffset The number of row to relocate. + means relocate upward
     * @param verbose   To print more information
     */
    public static void relocate (Bitstream bitstream, Series series, int rowOffset, boolean verbose) {

        List<Packet> newPackets = new ArrayList<>();
        for (Packet packet : bitstream.getPackets()) {
            if (packet.isTypeOnePacket() && (packet.getRegister() == RegisterType.FAR)) {
                if (verbose) System.out.print("word count " + packet.getWordCount());
                if (packet.getWordCount() == 1) {
                    int [] data = packet.getData();
                    int blkType = FAR.getBlockType(data[0],series);
                    if (verbose) {
                        System.out.print("  data " + toHexString(data) + " " +Arrays.toString(data));
                        System.out.print("  subblk " + blkType);
                    }
                    int rowAddr = FAR.getRowAddress(data[0], series);

                    if ((blkType == 0) || (blkType == 1)) {
                        int newRowAddr = rowAddr + rowOffset;
                        // there is no FAR.setRowAddress()
                        int newData = (data[0] & ~series.getRowMask()) | (newRowAddr << series.getRowLSB());
                        newPackets.add(new Packet(packet.getHeader(), newData));
                        if (verbose) System.out.print("  update row address " + newRowAddr +"\n");
                    } else {
                        if (verbose) System.out.print("  blkType " + blkType + "  no change.\n");
                        newPackets.add(packet);
                    }
                }
            } else {
                newPackets.add(packet);
            }
        }


        bitstream.setPackets(newPackets);
    }


    public static void main(String[] args) {
        String usage = String.join(System.getProperty("line.separator"),
                " Relocate a partial bitstream by the specified number of rows for an UltraScale/UltraScale+ device.",
                "",
                " The following conditions are necessary for the resulting bitstream to work on the board.",
                " 1) Both the source and target are DFX regions and their routing footprints are of the same size.",
                " 2) The interfaces to both regions are physically the same.",
                " 3) The interfaces to both regions must use the same memory address or the different bits are don't care bits in the region.",
                " 4) The interfaces between these regions and the static region use different clocks with proper CDC circuit.",
                " 5) The clocks used in the static region must not go into these regions.",
                " 6) The clocks route from static to these regions must be uniform across the two regions, either through HROUTE or HDISTR.",
                "    The clock track used must also be the same among the regions.",
                " 7) The clocks in each region must be driven by BUFG in the region or driven from the static region.",
                "    In the later case, only one clock route per clock is allowed to each region and Condition 6 must be met.",
                "",
                "Usage",
                "RelocateBitstreamByRow",
                "  -fr   <name of the intput bitstream (with extension)>",
                "  -to   <name of the output bitstream (with extension)>",
                "        <number of rows to move. +2 is up 2 rows, -2 is down 2 rows>",
                "  -series <optional option to specify the FPGA series. The valid options are US+, UltraScale+, US and UltraScale>"
                );
/*  example
-fr AES128_inst_RP2.bit
-to AES128_inst_RP1.bit -2
-fr nov1_2_dcpreloc_pblock_2_partial.bit -to nov1_2_dcpreloc_pblock_2_partial_torp0.bit -4
 */

        if (args.length < 5) {
            int i = 0;
            while (i < args.length) {
                // check flags
                switch (args[i]) {
                    case "-help":
                    case "-h":
                        System.out.println(usage);
                        System.exit(0);
                        break;
                    default:
                        break;
                }
                i++;
            }

            System.out.println(usage);
            System.exit(1);
        }

        long startTime = System.nanoTime();

        String inBit  = null;
        String outBit = null;
        int rowOffset = 0;
        boolean verbose = false;
        Series series = null;

        // collect command line arguments
        int i = 0;
        while (i < args.length) {
            // check flags
            switch (args[i]) {
                case "-fr":
                    inBit = args[++i];
                    break;
                case "-to":
                    outBit  = args[++i];
                    if (i < args.length) {
                        rowOffset   = Integer.parseInt(args[++i]);
                    } else {
                        System.out.println("Missing value for option -to.");
                        System.out.println(usage);
                        System.exit(1);
                    }
                    break;
                case "-series":
                    String seriesText = args[++i].toUpperCase();
                    if (seriesText.equals("US+") || seriesText.equals("ULTRASCALE+"))
                        series = Series.UltraScalePlus;
                    else if (seriesText.equals("US") || seriesText.equals("ULTRASCALE"))
                        series = Series.UltraScale;
                    else {
                        System.out.println("Invalid option for -series.");
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

        // report collected arguments
        System.out.println("RelocateBitstreamByRow");
        System.out.println("  -fr " + inBit);
        System.out.println("  -to " + outBit + " " + rowOffset);
        if (series != null)
            System.out.println("  -series " + series.name());


        Bitstream bitstream = Bitstream.readBitstream(inBit);

        if (series == null) {
            // This take 1 s on it own, making the whole process take 1370ms instead of 360ms, 4x!
            series = bitstream.getSeries();
            System.out.println("  -series is not set. The series inferred from " + inBit + " is " + series.name());
        }

        relocate (bitstream, series, rowOffset, verbose);
        updateCRC(bitstream, verbose);

        bitstream.writeBitstream(outBit);
        long stopTime = System.nanoTime();
        System.out.println("\ntook " + (stopTime - startTime)*1e-6 + " ms\ndone.");
    }
}
