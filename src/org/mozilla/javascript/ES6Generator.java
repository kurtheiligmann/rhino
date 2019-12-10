/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

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
        if (id == SymbolId_iterator) {
            initPrototypeMethod(GENERATOR_TAG, id, SymbolKey.ITERATOR, "[Symbol.iterator]", 0);
            return;
        }

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
        Object value = args.length >= 1 ? args[0] : Undefined.instance;

        switch (id) {
            case Id_return:
                return generator.resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_CLOSE, value);
            case Id_next:
                return generator.resume(cx, scope, value);
            case Id_throw:
                return generator.resumeThrow(cx, scope, value);
            case SymbolId_iterator:
                return thisObj;
            default:
                throw new IllegalArgumentException(String.valueOf(id));
        }
    }

    private Scriptable resume(Context cx, Scriptable scope, Object value)
    {
        if (delegee != null) {
            // Be super-careful and only pass an arg to next if it expects one
            final Object[] nextArgs =
                Undefined.instance.equals(value) ? ScriptRuntime.emptyArgs : new Object[] { value };

            final Callable nextFn = ScriptRuntime.getPropFunctionAndThis(delegee, "next", cx, scope);
            final Scriptable nextThis = ScriptRuntime.lastStoredScriptable(cx);
            final Object nr = nextFn.call(cx, scope, nextThis, nextArgs);

            final Scriptable nextResult = ScriptableObject.ensureScriptable(nr);
            if (ScriptRuntime.isIteratorDone(cx, nextResult)) {
                // Iterator is "done".
                delegee = null;
                // Return a result to the original generator
                return resumeLocal(cx, scope, ScriptableObject.getProperty(nextResult, "value"));
            }
            // Otherwise, we have a normal result and should continue
            return nextResult;
        }

        // If we get here then no delegees had a result or there were no delegees
        return resumeLocal(cx, scope, value);
    }

    private Scriptable resumeThrow(Context cx, Scriptable scope, Object value) {
        if (delegee != null) {
            // Delegate to "throw" method. If it's not defined we'll get an error here.
            final Callable throwFn = ScriptRuntime.getPropFunctionAndThis(delegee, "throw", cx, scope);
            final Scriptable nextThis = ScriptRuntime.lastStoredScriptable(cx);
            final Object tr = throwFn.call(cx, scope, nextThis, new Object[] { value });

            final Scriptable throwResult = ScriptableObject.ensureScriptable(tr);
            if (ScriptRuntime.isIteratorDone(cx, throwResult)) {
                // Iterator is "done".
                delegee = null;
                // Return a result to the original generator
                return resumeLocal(cx, scope, ScriptableObject.getProperty(throwResult, "value"));
            }
            // Otherwise, we have a normal result and should continue
            return throwResult;
        }

        return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, value);
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
                delegee = ScriptRuntime.callIterator(ysResult.getResult(), cx, scope);
                
                state = State.SUSPENDED_YIELD;
                Scriptable delResult;
                try {
                    // Re-execute but update state in case we end up back here
                    // TODO consider returning a different value from this function
                    // to avoid recursion.
                    delResult = resume(cx, scope, value);
                } finally {
                    state = State.EXECUTING;
                }
                if (ScriptRuntime.isIteratorDone(cx, delResult)) {
                    state = State.COMPLETED;
                }
                return delResult;
            }

            ScriptableObject.putProperty(result, "value", r);

        } catch (NativeGenerator.GeneratorClosedException gce) {
            state = State.COMPLETED;
        } catch (JavaScriptException jse) {
            state = State.COMPLETED;
            if (jse.getValue() instanceof NativeIterator.StopIteration) {
                ScriptableObject.putProperty(result, "value", 
                  ((NativeIterator.StopIteration)jse.getValue()).getValue());
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
                ScriptableObject.putProperty(result, "done", true);
            } else {
                state = State.SUSPENDED_YIELD;
            }
        }
        return result;
    }

    private Scriptable resumeAbruptLocal(Context cx, Scriptable scope, int op, Object value)
    {
        if (state == State.EXECUTING) {
            throw ScriptRuntime.typeError0("msg.generator.executing");
        }
        if (state == State.SUSPENDED_START) {
            // Throw right away if we never started
            state = State.COMPLETED;
        }

        final Scriptable result = ES6Iterator.makeIteratorResult(cx, scope, false);
        if (state == State.COMPLETED) {
            if (op == NativeGenerator.GENERATOR_THROW) {
                throw new JavaScriptException(value, lineSource, lineNumber);
            }
            ScriptableObject.putProperty(result, "done", true);
        }

        state = State.EXECUTING;

        try {
            Object r = function.resumeGenerator(cx, scope, op, savedState, value);
            ScriptableObject.putProperty(result, "value", r);
            // If we get here without an exception we can still run.
            state = State.SUSPENDED_YIELD;

        } catch (NativeGenerator.GeneratorClosedException gce) {
            state = State.COMPLETED;
        } catch (JavaScriptException jse) {
            state = State.COMPLETED;
            if (!(jse.getValue() instanceof NativeIterator.StopIteration)) {
                lineNumber = jse.lineNumber();
                lineSource = jse.lineSource();
                throw jse;
            }
        } catch (RhinoException re) {
            state = State.COMPLETED;
            lineNumber = re.lineNumber();
            lineSource = re.lineSource();
            throw re;
        } finally {
            // After an abrupt completion we are always, umm, complete,
            // and we will never delegate to the delegee again
            if (state == State.COMPLETED) {
                delegee = null;
                ScriptableObject.putProperty(result, "done", true);
            }
        }
        return result;
    }

    @Override
    protected int findPrototypeId(Symbol k) {
        if (SymbolKey.ITERATOR.equals(k)) {
            return SymbolId_iterator;
        }
        return 0;
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
            SymbolId_iterator        = 4,
            MAX_PROTOTYPE_ID         = SymbolId_iterator;

    // #/string_id_map#

    private NativeFunction function;
    private Object savedState;
    private String lineSource;
    private int lineNumber;
    private State state = State.SUSPENDED_START;
    private Object delegee;

    enum State { SUSPENDED_START, SUSPENDED_YIELD, EXECUTING, COMPLETED };

    public static final class YieldStarResult {
        private Object result;

        public YieldStarResult(Object result) {
            this.result = result;
        }

        Object getResult() { return result; }
    }
}
