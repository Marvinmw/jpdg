package edu.cwru.jpdg;

/* Tim Henderson (tadh@case.edu)
 *
 * This file is part of jpdg a library to generate Program Dependence Graphs
 * from JVM bytecode.
 *
 * Copyright (c) 2014, Tim Henderson, Case Western Reserve University
 *   Cleveland, Ohio 44106
 *   All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc.,
 *   51 Franklin Street, Fifth Floor,
 *   Boston, MA  02110-1301
 *   USA
 * or retrieve version 2.1 at their website:
 *   http://www.gnu.org/licenses/lgpl-2.1.html
 */

import java.util.*;
import java.io.*;
import java.nio.charset.Charset;

import soot.options.Options;

import soot.jimple.toolkits.callgraph.CHATransformer;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Targets;

import soot.toolkits.graph.Block;
import soot.toolkits.graph.BlockGraph;
import soot.toolkits.graph.BriefBlockGraph;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.MHGPostDominatorsFinder;
import soot.toolkits.graph.DominatorNode;
import soot.toolkits.graph.CytronDominanceFrontier;
import soot.toolkits.graph.pdg.EnhancedBlockGraph;
import soot.toolkits.graph.pdg.MHGDominatorTree;

import soot.toolkits.scalar.SimpleLiveLocals;
import soot.toolkits.scalar.SmartLocalDefs;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;

import soot.jimple.DefinitionStmt;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.internal.JimpleLocalBox;

import edu.cwru.jpdg.graph.Graph;

public class JPDG {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Must supply classpath and base-dir");
            System.exit(1);
        }
        String cp = args[0];
        String base_dir = args[1];
        List<String> dirs = new ArrayList<String>();
        dirs.add(base_dir);
        List<String> excluded = new ArrayList<String>();
        // excluded.add("java");
        // excluded.add("sun");
        // excluded.add("org.hamcrest");

        soot.PackManager.v().getPack("wjtp").add(new soot.Transform("wjtp.myTrans", new soot.SceneTransformer() {
            @Override
            protected void internalTransform(String phaseName, Map options) {
                CHATransformer.v().transform();
                CallGraph cg = soot.Scene.v().getCallGraph();
            }
        }));

        soot.Scene S = soot.Scene.v();
        Options O = Options.v();

        O.set_soot_classpath(cp);
        O.set_process_dir(dirs);
        O.set_exclude(excluded);
        // O.set_whole_program(true);
        // O.setPhaseOption("cg.spark", "enabled:true");
        // O.setPhaseOption("wjtp", "enabled:true");
        // O.setPhaseOption("wjtp.myTrans", "enabled:true");
        // O.setPhaseOption("jop", "enabled:true");
        // O.setPhaseOption("bb", "enabled:false");
        O.set_output_format(O.output_format_jimple);
        O.set_keep_line_number(true);
        O.set_keep_offset(true);
        O.set_ignore_resolution_errors(true);
        O.set_verbose(false);
        // O.set_app(true);


        S.loadNecessaryClasses();
        soot.PackManager.v().runPacks();




        soot.util.Chain<soot.SootClass> classes = S.getApplicationClasses();

        Graph g = new Graph();

        for (soot.SootClass c : classes) {
            process_class(g, c);
        }

        // System.out.println();
        // for (soot.SootMethod m : S.getEntryPoints()) {
            // System.out.println(m);
        // }

        // System.out.println();
        // S.getCallGraph();

        byte[] graph = g.Serialize().getBytes(Charset.forName("UTF-8"));

        FileOutputStream s = null;
        try {
            s = new FileOutputStream("out.pdg");
            s.write(graph);
        } catch (IOException ex) {
            System.err.println(ex);
        } finally {
           try {s.close();} catch (Exception ex) {}
        }
    }

    public static void process_class(Graph g, soot.SootClass c) {
        for (soot.SootMethod m : c.getMethods()) {
            try {
                soot.Body body = m.retrieveActiveBody();
                // ExceptionalBlockGraph ebg = new ExceptionalBlockGraph(body);
                BlockGraph ebg = new EnhancedBlockGraph(body);
                add_cfg_cdg(g, c, m, body, ebg);
            } catch (Exception e) {
                System.err.println(e);
            }
        }
    }

    public static void add_cfg_cdg(Graph g, soot.SootClass c, soot.SootMethod m, soot.Body body, BlockGraph cfg) {
        // This uses the CDG algorithm from the Cytron paper.

        MHGPostDominatorsFinder pdf = new MHGPostDominatorsFinder(cfg);
        MHGDominatorTree pdom_tree = new MHGDominatorTree(pdf);
        CytronDominanceFrontier rdf = new CytronDominanceFrontier(pdom_tree);

        int entry_uid = g.addNode(
            c.getPackageName() + c.getName() + m.getName() + "_entry",
            c.getPackageName(), c.getName(), m.getName(),
            m.getJavaSourceStartLineNumber(),
            m.getJavaSourceStartColumnNumber(),
            m.getJavaSourceStartLineNumber(),
            m.getJavaSourceStartColumnNumber()
        );

        // make nodes for every basic block
        HashMap<Integer,Integer> block_uids = new HashMap<Integer,Integer>();
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            Block b = i.next();
            int uid = g.addNode(
                c.getPackageName() + c.getName() + m.getName() + b.getIndexInMethod(),
                c.getPackageName(), c.getName(), m.getName(),
                b.getHead().getJavaSourceStartLineNumber(),
                b.getHead().getJavaSourceStartColumnNumber(),
                b.getTail().getJavaSourceStartLineNumber(),
                b.getTail().getJavaSourceStartColumnNumber()
            );
            block_uids.put(b.getIndexInMethod(), uid);
        }

        HashMap<soot.Unit,Block> unit_to_blk = new HashMap<soot.Unit,Block>();
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            Block b = i.next();
            int b_uid = block_uids.get(b.getIndexInMethod());
            for (Iterator<soot.Unit> iu = b.iterator(); iu.hasNext(); ) {
                soot.Unit u = iu.next();
                unit_to_blk.put(u, b);
            }
        }

        // add a path from the entry to each head in the graph
        for (Block head : cfg.getHeads()) {
            int head_uid = block_uids.get(head.getIndexInMethod());
            g.addEdge(entry_uid, head_uid, "cfg");
        }

        // add cfg edges
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            Block b = i.next();
            int uid_i = block_uids.get(b.getIndexInMethod());
            for (Block s : b.getSuccs()) {
                int uid_s = block_uids.get(s.getIndexInMethod());
                g.addEdge(uid_i, uid_s, "cfg");
            }
        }

        // initialize a map : uids -> bool indicating if there is a parent for
        // the block in the cdg. If there isn't it is dependent on the dummy
        // entry node.
        HashMap<Integer,Boolean> has_parent = new HashMap<Integer,Boolean>();
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            Block y = i.next();
            int uid_y = block_uids.get(y.getIndexInMethod());
            has_parent.put(uid_y, false);
        }

        // using Cytrons algorithm for each block, y, is dependent on another
        // block, x, if x appears in y post-domanance frontier.
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            Block y = i.next();
            int uid_y = block_uids.get(y.getIndexInMethod());
            for (Object o : rdf.getDominanceFrontierOf(pdom_tree.getDode(y))) {
                Block x = ((Block)((DominatorNode)o).getGode());
                int uid_x = block_uids.get(x.getIndexInMethod());
                g.addEdge(uid_x, uid_y, "cdg");
                if (uid_x != uid_y) {
                    has_parent.put(uid_y, true);
                }
            }
        }

        // finally all of those blocks without parents need to become dependent
        // on the entry to the procedure.
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            Block y = i.next();
            int uid_y = block_uids.get(y.getIndexInMethod());
            if (!has_parent.get(uid_y)) {
                g.addEdge(entry_uid, uid_y, "cdg");
            }
        }

        System.out.println(c.getPackageName() + " " + c.getName() + " " + m.getName());
        HashMap<Integer,List<soot.ValueBox>> live_vars = new HashMap<Integer,List<soot.ValueBox>>();
        BriefUnitGraph bug = new BriefUnitGraph(body);
        SimpleLiveLocals sll = new SimpleLiveLocals(bug);
        SmartLocalDefs sld = new SmartLocalDefs(bug, sll);
        SimpleLocalUses slu = new SimpleLocalUses(bug, sld);
        for (Iterator<Block> i = cfg.iterator(); i.hasNext(); ) {
            HashMap<Integer,List<DefinitionStmt>> defining_stmts = new HashMap<Integer,List<DefinitionStmt>>();
            Block b = i.next();
            int uid_b = block_uids.get(b.getIndexInMethod());
            System.out.println("Block" + b.getIndexInMethod());
            for (Iterator<soot.Unit> it = b.iterator(); it.hasNext(); ) {
                soot.Unit u = it.next();
                System.out.println(u);
                if (u instanceof DefinitionStmt) {
                    DefinitionStmt def_stmt = (DefinitionStmt)u;
                    JimpleLocal var  = (JimpleLocal)def_stmt.getLeftOp();
                    System.out.print("++ ");
                    printJimpleLocal(var);
                    System.out.println();
                    if (!defining_stmts.containsKey(var.getNumber())) {
                        defining_stmts.put(var.getNumber(), new ArrayList<DefinitionStmt>());
                    }
                    defining_stmts.get(var.getNumber()).add(def_stmt);
                }
            }
            System.out.println();
            List<JimpleLocal> values = sll.getLiveLocalsAfter(b.getTail());
            System.out.print("block tail " + b.getTail());
            System.out.print(">> ");
            for (JimpleLocal value : values) {
                printJimpleLocal(value);
                System.out.print(" ");
                if (defining_stmts.containsKey(value.getNumber())) {
                    List<DefinitionStmt> def_stmts =  defining_stmts.get(value.getNumber());
                    for (DefinitionStmt def_stmt : def_stmts) {
                        System.out.print(def_stmt + " ");
                        List<UnitValueBoxPair> uses = slu.getUsesOf(def_stmt);
                        for (UnitValueBoxPair u : uses) {
                            Block ub = unit_to_blk.get(u.unit);
                            int uid_ub = block_uids.get(ub.getIndexInMethod());
                            System.out.print("{" + u.unit + "::block-" + ub.getIndexInMethod() + "} ");
                            if (uid_b != uid_ub) {
                                g.addEdge(uid_b, uid_ub, "ddg");
                            }
                        }
                        System.out.print("; ");
                    }
                    /* Now all we need to do is find the blocks the which use
                     * the value from the defining_stmt and hook them up.
                     */
                } else {
                    System.out.print("no-def-in-block");
                }
                System.out.print(", ");
            }
            System.out.println();
            System.out.println();
            System.out.println();

        }
        System.out.println();
    }

    public static void printJimpleLocal(JimpleLocal jl) {
        System.out.print(jl.getName());
        System.out.print(":");
        System.out.print(jl.getType());
        System.out.print(":");
        System.out.print(jl.getNumber());
    }
}
