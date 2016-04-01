/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.truffle.test.builtins;

import java.util.HashSet;
import java.util.Set;

import com.oracle.graal.truffle.GraalTruffleRuntime;
import com.oracle.graal.truffle.OptimizedCallTarget;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.sl.builtins.SLBuiltinNode;
import com.oracle.truffle.sl.runtime.SLContext;

public abstract class SLGraalRuntimeBuiltin extends SLBuiltinNode {

    protected SLGraalRuntimeBuiltin(SourceSection sourceSection) {
        super(sourceSection);
        if (!(Truffle.getRuntime() instanceof GraalTruffleRuntime)) {
            throw new AssertionError("Graal runtime builtins can only be used inside of a Graal runtime.");
        }
    }

    protected SLGraalRuntimeBuiltin(Class<? extends SLGraalRuntimeBuiltin> clazz) {
        this(sourceSectionFromClass(clazz));
    }

    protected static SourceSection sourceSectionFromClass(Class<? extends SLGraalRuntimeBuiltin> clazz) {
        return SourceSection.createUnavailable("SL Builtin", SLContext.lookupNodeInfo(clazz).shortName());
    }

    /**
     * Finds all call targets available for the same original call target. This might be useful if a
     * {@link CallTarget} got duplicated due to splitting.
     */
    @TruffleBoundary
    protected static final Set<OptimizedCallTarget> findDuplicateCallTargets(OptimizedCallTarget originalCallTarget) {
        final Set<OptimizedCallTarget> allCallTargets = new HashSet<>();
        allCallTargets.add(originalCallTarget);
        for (RootCallTarget target : Truffle.getRuntime().getCallTargets()) {
            if (target instanceof OptimizedCallTarget) {
                OptimizedCallTarget oct = (OptimizedCallTarget) target;
                if (oct.getSourceCallTarget() == originalCallTarget) {
                    allCallTargets.add(oct);
                }
            }
        }
        return allCallTargets;
    }

    /**
     * Finds all {@link DirectCallNode} instances calling a certain original {@link CallTarget} in
     * the caller function.
     */
    @TruffleBoundary
    protected static final Set<DirectCallNode> findCallsTo(OptimizedCallTarget originalCallTarget) {
        FrameInstance frame = Truffle.getRuntime().getCallerFrame();
        RootNode root = frame.getCallNode().getRootNode();
        return findCallsTo(root, originalCallTarget);
    }

    /**
     * Finds all {@link DirectCallNode} instances calling a certain original {@link CallTarget} in a
     * given {@link RootNode}.
     */
    @TruffleBoundary
    protected static final Set<DirectCallNode> findCallsTo(RootNode root, OptimizedCallTarget originalCallTarget) {
        final Set<DirectCallNode> allCallNodes = new HashSet<>();
        root.accept(new NodeVisitor() {
            @Override
            public boolean visit(Node node) {
                if (node instanceof DirectCallNode) {
                    DirectCallNode callNode = (DirectCallNode) node;
                    if (callNode.getCallTarget() == originalCallTarget || callNode.getClonedCallTarget() == originalCallTarget) {
                        allCallNodes.add(callNode);
                    }
                }
                return true;
            }
        });
        return allCallNodes;
    }
}
