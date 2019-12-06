/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.ArrayDeque;

public final class ES6Generator extends IdScriptableObject {
    private static final long serialVersionUID = 1645892441041347273L;

    private static final Object GENERATOR_TAG = "Generator";

    static ES6Generator init(ScriptableObject scope, boolean sealed) {

        ES6Generator prototype = new ES6Generator();
        if (scope != null) {
            prototype.setParentScope(scope);
            prototype.setPrototype(getObjectPrototype(scope));
        }
        prototype.activatePrototypeMap(MAX_PROTOTYPE_ID);
        if (sealed) {
            prototype.sealObject();
        }

        // Need to access Generator prototype when constructing
        // Generator instances, but don't have a generator constructor
        // to use to find the prototype. Use the "associateValue"
        // approach instead.
        if (scope != null) {
            scope.associateValue(GENERATOR_TAG, prototype);
        }

        return prototype;
    }

    /**
     * Only for constructing the prototype object.
     */
    private ES6Generator() { }

    public ES6Generator(Scriptable scope, NativeFunction function,
                        Object savedState)
    {
        this.function = function;
        this.savedState = savedState;
        // Set parent and prototype properties. Since we don't have a
        // "Generator" constructor in the top scope, we stash the
        // prototype in the top scope's associated value.
        Scriptable top = ScriptableObject.getTopLevelScope(scope);
        this.setParentScope(top);
        ES6Generator prototype =
            (ES6Generator)ScriptableObject.getTopScopeValue(top, GENERATOR_TAG);
        this.setPrototype(prototype);
    }

    @Override
    public String getClassName() {
        return "Generator";
    }

    @Override
    protected void initPrototypeId(int id) {
        String s;
        int arity;
        switch (id) {
            case Id_next:           arity=1; s="next";           break;
            case Id_return:         arity=1; s="return";         break;
            case Id_throw:          arity=1; s="throw";          break;
            default: throw new IllegalArgumentException(String.valueOf(id));
        }
        initPrototypeMethod(GENERATOR_TAG, id, s, arity);
    }

    @Override
    public Object execIdCall(IdFunctionObject f, Context cx, Scriptable scope,
                             Scriptable thisObj, Object[] args)
    {
        if (!f.hasTag(GENERATOR_TAG)) {
            return super.execIdCall(f, cx, scope, thisObj, args);
        }
        int id = f.methodId();

        if (!(thisObj instanceof ES6Generator)) {
            throw incompatibleCallError(f);
        }

        ES6Generator generator = (ES6Generator) thisObj;
        Scriptable result;
        Object value = args.length >= 1 ? args[0] : Undefined.instance;

        switch (id) {
            case Id_return:
                result = generator.resumeAbrupt(cx, scope, NativeGenerator.GENERATOR_CLOSE, value);
                break;
            case Id_next:
                result = generator.resume(cx, scope, value);
                break;
            case Id_throw:
                result = generator.resumeAbrupt(cx, scope, NativeGenerator.GENERATOR_THROW, value);
                break;
            default:
                throw new IllegalArgumentException(String.valueOf(id));
        }

        return result;
    }

    private Scriptable resume(Context cx, Scriptable scope, Object value)
    {
        while (!delegees.isEmpty()) {
            // We have used "yield *" previously to delegate to another generator
            Scriptable delResult = delegees.peek().resume(cx, scope, value); 
            if (ScriptRuntime.toBoolean(ScriptableObject.getProperty(delResult, ES6Iterator.DONE_PROPERTY))) {
                // That one is done. Keep looping.
                delegees.pop();
            } else {
                return delResult;
            }
        }

        // If we get here then no delegees had a result or there were no delegees
        return resumeLocal(cx, scope, value);
    }

    private Scriptable resumeLocal(Context cx, Scriptable scope, Object value)
    {
        if (state == State.COMPLETED) {
            return ES6Iterator.makeIteratorResult(cx, scope, true);
        }
        if (state == State.EXECUTING) {
            throw ScriptRuntime.typeError0("msg.generator.executing");
        }

        final Scriptable result = ES6Iterator.makeIteratorResult(cx, scope, false);
        state = State.EXECUTING;

        try {
            Object r = function.resumeGenerator(cx, scope,
                NativeGenerator.GENERATOR_SEND, savedState, value);

            if (r instanceof YieldStarResult) {
                // This special result tells us that we are executing a "yield *"
                final YieldStarResult ysResult = (YieldStarResult)r;
                try {
                    delegees.push((ES6Generator)(ysResult.getResult()));
                    return resume(cx, scope, value);
                } catch (ClassCastException cce) {
                    throw ScriptRuntime.typeError0("msg.yield.star.not.generator");
                }
            }

            ScriptableObject.putProperty(result, ES6Iterator.VALUE_PROPERTY, r);

        } catch (NativeGenerator.GeneratorClosedException gce) {
            state = State.COMPLETED;
        } catch (JavaScriptException jse) {
            if (jse.getValue() instanceof NativeIterator.StopIteration) {
                ScriptableObject.putProperty(result, ES6Iterator.VALUE_PROPERTY, 
                  ((NativeIterator.StopIteration)jse.getValue()).getValue());
                state = State.COMPLETED;
            } else {
                lineNumber = jse.lineNumber();
                lineSource = jse.lineSource();
                throw jse;
            }
        } catch (RhinoException re) {
            lineNumber = re.lineNumber();
            lineSource = re.lineSource();
            throw re;
        } finally {
            if (state == State.COMPLETED) {
                ScriptableObject.putProperty(result, ES6Iterator.DONE_PROPERTY, true);
            } else {
                state = State.SUSPENDED_YIELD;
            }
        }
        return result;
    }

    private Scriptable resumeAbrupt(Context cx, Scriptable scope, int op, Object value)
    {
        final Scriptable result = ES6Iterator.makeIteratorResult(cx, scope, false);
        if (state == State.EXECUTING) {
            throw ScriptRuntime.typeError0("msg.generator.executing");
        }
        if (state == State.SUSPENDED_START) {
            // Throw right away if we never started
            state = State.COMPLETED;
        }
        if (state == State.COMPLETED) {
            if (op == NativeGenerator.GENERATOR_THROW) {
                throw new JavaScriptException(value, lineSource, lineNumber);
            }
            ScriptableObject.putProperty(result, ES6Iterator.DONE_PROPERTY, true);
        }

        state = State.EXECUTING;

        try {
            Object r = function.resumeGenerator(cx, scope, op, savedState, value);
            ScriptableObject.putProperty(result, ES6Iterator.VALUE_PROPERTY, r);

        } catch (NativeGenerator.GeneratorClosedException gce) {
            state = State.COMPLETED;
        } catch (JavaScriptException jse) {
            if (jse.getValue() instanceof NativeIterator.StopIteration) {
                state = State.COMPLETED;
            } else {
                lineNumber = jse.lineNumber();
                lineSource = jse.lineSource();
                throw jse;
            }
        } catch (RhinoException re) {
            lineNumber = re.lineNumber();
            lineSource = re.lineSource();
            throw re;
        } finally {
            // After an abrupt completion we are always, umm, complete.
            state = State.COMPLETED;
            ScriptableObject.putProperty(result, ES6Iterator.DONE_PROPERTY, true);
        }
        return result;
    }

// #string_id_map#

    @Override
    protected int findPrototypeId(String s) {
        int id;
// #generated# Last update: 2017-08-04 17:30:46 PDT
        L0: { id = 0; String X = null;
            int s_length = s.length();
            if (s_length==4) { X="next";id=Id_next; }
            else if (s_length==5) { X="throw";id=Id_throw; }
            else if (s_length==6) { X="return";id=Id_return; }
            if (X!=null && X!=s && !X.equals(s)) id = 0;
            break L0;
        }
// #/generated#
        return id;
    }

    private static final int
            Id_next                  = 1,
            Id_return                = 2,
            Id_throw                 = 3,
            MAX_PROTOTYPE_ID         = Id_throw;

    // #/string_id_map#

    private NativeFunction function;
    private Object savedState;
    private String lineSource;
    private int lineNumber;
    private State state = State.SUSPENDED_START;
    private final ArrayDeque<ES6Generator> delegees = new ArrayDeque<>(0);

    enum State { SUSPENDED_START, SUSPENDED_YIELD, EXECUTING, COMPLETED };

    public static final class YieldStarResult {
        private Object result;

        public YieldStarResult(Object result) {
            this.result = result;
        }

        Object getResult() { return result; }
    }
}
