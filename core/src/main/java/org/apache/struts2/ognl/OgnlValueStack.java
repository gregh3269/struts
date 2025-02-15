/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.struts2.ognl;

import org.apache.struts2.ActionContext;
import org.apache.struts2.text.TextProvider;
import org.apache.struts2.conversion.impl.XWorkConverter;
import org.apache.struts2.inject.Container;
import org.apache.struts2.inject.Inject;
import org.apache.struts2.ognl.accessor.RootAccessor;
import org.apache.struts2.util.ClearableValueStack;
import org.apache.struts2.util.CompoundRoot;
import org.apache.struts2.util.MemberAccessValueStack;
import org.apache.struts2.util.ValueStack;
import org.apache.struts2.util.reflection.ReflectionContextState;
import ognl.MethodFailedException;
import ognl.NoSuchPropertyException;
import ognl.Ognl;
import ognl.OgnlContext;
import ognl.OgnlException;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.struts2.StrutsConstants;
import org.apache.struts2.StrutsException;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ognl implementation of a value stack that allows for dynamic Ognl expressions to be evaluated against it. When evaluating an expression,
 * the stack will be searched down the stack, from the latest objects pushed in to the earliest, looking for a bean with a getter or setter
 * for the given property or a method of the given name (depending on the expression being evaluated).
 *
 * @author Patrick Lightbody
 * @author tm_jee
 * @version $Date$ $Id$
 */
public class OgnlValueStack implements Serializable, ValueStack, ClearableValueStack, MemberAccessValueStack {

    public static final String THROW_EXCEPTION_ON_FAILURE = OgnlValueStack.class.getName() + ".throwExceptionOnFailure";

    private static final Logger LOG = LogManager.getLogger(OgnlValueStack.class);

    @Serial
    private static final long serialVersionUID = 370737852934925530L;

    private static final String MAP_IDENTIFIER_KEY = "org.apache.struts2.util.OgnlValueStack.MAP_IDENTIFIER_KEY";

    protected CompoundRoot root;
    protected transient Map<String, Object> context;
    protected Class defaultType;
    protected Map<Object, Object> overrides;
    protected transient OgnlUtil ognlUtil;
    protected transient SecurityMemberAccess securityMemberAccess;

    private transient XWorkConverter converter;
    private boolean devMode;
    private boolean logMissingProperties;
    private boolean shouldFallbackToContext = true;

    /**
     * @since 6.4.0
     */
    protected OgnlValueStack(ValueStack vs,
                             XWorkConverter xworkConverter,
                             RootAccessor accessor,
                             TextProvider prov,
                             SecurityMemberAccess securityMemberAccess) {
        setRoot(xworkConverter,
                accessor,
                vs != null ? new CompoundRoot(vs.getRoot()) : new CompoundRoot(),
                securityMemberAccess);
        if (prov != null) {
            push(prov);
        }
    }

    /**
     * @since 6.4.0
     */
    protected OgnlValueStack(XWorkConverter xworkConverter, RootAccessor accessor, TextProvider prov, SecurityMemberAccess securityMemberAccess) {
        this(null, xworkConverter, accessor, prov, securityMemberAccess);
    }

    /**
     * @since 6.4.0
     */
    protected OgnlValueStack(ValueStack vs, XWorkConverter xworkConverter, RootAccessor accessor, SecurityMemberAccess securityMemberAccess) {
        this(vs, xworkConverter, accessor, null, securityMemberAccess);
    }

    @Inject
    protected void setOgnlUtil(OgnlUtil ognlUtil) {
        this.ognlUtil = ognlUtil;
    }

    /**
     * @since 6.4.0
     */
    protected void setRoot(XWorkConverter xworkConverter, RootAccessor accessor, CompoundRoot compoundRoot, SecurityMemberAccess securityMemberAccess) {
        this.root = compoundRoot;
        this.securityMemberAccess = securityMemberAccess;
        this.context = Ognl.createDefaultContext(this.root, securityMemberAccess, accessor, new OgnlTypeConverterWrapper(xworkConverter));
        this.converter = xworkConverter;
        context.put(VALUE_STACK, this);
        ((OgnlContext) context).setTraceEvaluations(false);
        ((OgnlContext) context).setKeepLastEvaluation(false);
    }

    @Inject(StrutsConstants.STRUTS_DEVMODE)
    protected void setDevMode(String mode) {
        this.devMode = BooleanUtils.toBoolean(mode);
    }

    @Inject(value = StrutsConstants.STRUTS_OGNL_LOG_MISSING_PROPERTIES, required = false)
    protected void setLogMissingProperties(String logMissingProperties) {
        this.logMissingProperties = BooleanUtils.toBoolean(logMissingProperties);
    }

    @Inject(value = StrutsConstants.STRUTS_OGNL_VALUE_STACK_FALLBACK_TO_CONTEXT, required = false)
    protected void setShouldFallbackToContext(String shouldFallbackToContext) {
        this.shouldFallbackToContext = BooleanUtils.toBoolean(shouldFallbackToContext);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#getContext()
     */
    @Override
    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public ActionContext getActionContext() {
        return ActionContext.of(context);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#setDefaultType(java.lang.Class)
     */
    @Override
    public void setDefaultType(Class defaultType) {
        this.defaultType = defaultType;
    }

    /**
     * @see org.apache.struts2.util.ValueStack#setExprOverrides(java.util.Map)
     */
    public void setExprOverrides(Map<Object, Object> overrides) {
        if (this.overrides == null) {
            this.overrides = overrides;
        } else {
            this.overrides.putAll(overrides);
        }
    }

    /**
     * @see org.apache.struts2.util.ValueStack#getExprOverrides()
     */
    @Override
    public Map<Object, Object> getExprOverrides() {
        return this.overrides;
    }

    /**
     * @see org.apache.struts2.util.ValueStack#getRoot()
     */
    @Override
    public CompoundRoot getRoot() {
        return root;
    }

    /**
     * @see org.apache.struts2.util.ValueStack#setParameter(String, Object)
     */
    @Override
    public void setParameter(String expr, Object value) {
        setValue(expr, value, devMode);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#setValue(java.lang.String, java.lang.Object)
     */
    @Override
    public void setValue(String expr, Object value) {
        setValue(expr, value, devMode);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#setValue(java.lang.String, java.lang.Object, boolean)
     */
    @Override
    public void setValue(String expr, Object value, boolean throwExceptionOnFailure) {
        Map<String, Object> context = getContext();
        try {
            trySetValue(expr, value, throwExceptionOnFailure, context);
        } catch (OgnlException e) {
            handleOgnlException(expr, value, throwExceptionOnFailure, e);
        } catch (RuntimeException re) { //XW-281
            handleRuntimeException(expr, value, throwExceptionOnFailure, re);
        } finally {
            cleanUpContext(context);
        }
    }

    private void trySetValue(String expr, Object value, boolean throwExceptionOnFailure, Map<String, Object> context) throws OgnlException {
        context.put(XWorkConverter.CONVERSION_PROPERTY_FULLNAME, expr);
        context.put(REPORT_ERRORS_ON_NO_PROP, throwExceptionOnFailure || logMissingProperties ? Boolean.TRUE : Boolean.FALSE);
        ognlUtil.setValue(expr, context, root, value);
    }

    private void cleanUpContext(Map<String, Object> context) {
        ReflectionContextState.clear(context);
        context.remove(XWorkConverter.CONVERSION_PROPERTY_FULLNAME);
        context.remove(REPORT_ERRORS_ON_NO_PROP);
    }

    protected void handleRuntimeException(String expr, Object value, boolean throwExceptionOnFailure, RuntimeException re) {
        if (throwExceptionOnFailure) {
            String message = ErrorMessageBuilder.create()
                    .errorSettingExpressionWithValue(expr, value)
                    .build();
            throw new StrutsException(message, re);
        } else {
            LOG.warn("Error setting value [{}] with expression [{}]", value, expr, re);
        }
    }

    protected void handleOgnlException(String expr, Object value, boolean throwExceptionOnFailure, OgnlException e) {
        if (e != null && e.getReason() instanceof SecurityException) {
            LOG.error("Could not evaluate this expression due to security constraints: [{}]", expr, e);
        }
    	boolean shouldLog = shouldLogMissingPropertyWarning(e);
    	String msg = null;
    	if (throwExceptionOnFailure || shouldLog) {
            msg = ErrorMessageBuilder.create().errorSettingExpressionWithValue(expr, value).build();
        }
        if (shouldLog) {
            LOG.warn(msg, e);
    	}

        if (throwExceptionOnFailure) {
            throw new StrutsException(msg, e);
        }
    }

    /**
     * @see org.apache.struts2.util.ValueStack#findString(java.lang.String)
     */
    @Override
    public String findString(String expr) {
        return (String) findValue(expr, String.class);
    }

    @Override
    public String findString(String expr, boolean throwExceptionOnFailure) {
        return (String) findValue(expr, String.class, throwExceptionOnFailure);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#findValue(java.lang.String)
     */
    @Override
    public Object findValue(String expr, boolean throwExceptionOnFailure) {
        try {
            setupExceptionOnFailure(throwExceptionOnFailure);
            return tryFindValueWhenExpressionIsNotNull(expr);
        } catch (OgnlException e) {
            return handleOgnlException(expr, throwExceptionOnFailure, e);
        } catch (Exception e) {
            return handleOtherException(expr, throwExceptionOnFailure, e);
        } finally {
            ReflectionContextState.clear(context);
        }
    }

    protected void setupExceptionOnFailure(boolean throwExceptionOnFailure) {
        if (throwExceptionOnFailure || logMissingProperties) {
            context.put(THROW_EXCEPTION_ON_FAILURE, true);
        }
    }

    private Object tryFindValueWhenExpressionIsNotNull(String expr) throws OgnlException {
        if (expr == null) {
            return null;
        }
        return tryFindValue(expr);
    }

    protected Object handleOtherException(String expr, boolean throwExceptionOnFailure, Exception e) {
        logLookupFailure(expr, e);

        if (throwExceptionOnFailure)
            throw new StrutsException(e);

        return findInContext(expr);
    }

    private Object tryFindValue(String expr) throws OgnlException {
        return tryFindValue(expr, defaultType);
    }

    private String lookupForOverrides(String expr) {
        if (overrides != null && overrides.containsKey(expr)) {
            expr = (String) overrides.get(expr);
        }
        return expr;
    }

    @Override
    public Object findValue(String expr) {
        return findValue(expr, false);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#findValue(java.lang.String, java.lang.Class)
     */
    @Override
    public Object findValue(String expr, Class asType, boolean throwExceptionOnFailure) {
        try {
            setupExceptionOnFailure(throwExceptionOnFailure);
            return tryFindValueWhenExpressionIsNotNull(expr, asType);
        } catch (OgnlException e) {
            final Object value = handleOgnlException(expr, throwExceptionOnFailure, e);
            return converter.convertValue(getContext(), value, asType);
        } catch (Exception e) {
            final Object value = handleOtherException(expr, throwExceptionOnFailure, e);
            return converter.convertValue(getContext(), value, asType);
        } finally {
            ReflectionContextState.clear(context);
        }
    }

    private Object tryFindValueWhenExpressionIsNotNull(String expr, Class asType) throws OgnlException {
        if (expr == null) {
            return null;
        }
        return tryFindValue(expr, asType);
    }

    protected Object handleOgnlException(String expr, boolean throwExceptionOnFailure, OgnlException e) {
        Object ret = null;
        if (e != null && e.getReason() instanceof SecurityException) {
            LOG.error("Could not evaluate this expression due to security constraints: [{}]", expr, e);
        } else {
            ret = findInContext(expr);
        }
        if (ret == null) {
            if (shouldLogMissingPropertyWarning(e)) {
                LOG.warn("Could not find property [{}]!", expr, e);
            }
            if (throwExceptionOnFailure) {
                throw new StrutsException(e);
            }
        }
        return ret;
    }

    protected boolean shouldLogMissingPropertyWarning(OgnlException e) {
        return (e instanceof NoSuchPropertyException ||
                (e instanceof MethodFailedException && e.getReason() instanceof NoSuchMethodException))
        		&& logMissingProperties;
    }

    private Object tryFindValue(String expr, Class asType) throws OgnlException {
        try {
            expr = lookupForOverrides(expr);
            Object value = ognlUtil.getValue(expr, context, root, asType);
            if (value == null) {
                value = findInContext(expr);
                if (value != null && asType != null) {
                    value = converter.convertValue(getContext(), value, asType);
                }
            }
            return value;
        } finally {
            context.remove(THROW_EXCEPTION_ON_FAILURE);
        }
    }

    protected Object findInContext(String name) {
        if (!shouldFallbackToContext) {
            return null;
        }
        return getContext().get(name);
    }

    @Override
    public Object findValue(String expr, Class asType) {
        return findValue(expr, asType, false);
    }

    /**
     * Log a failed lookup, being more verbose when devMode=true.
     *
     * @param expr The failed expression
     * @param e    The thrown exception.
     */
    private void logLookupFailure(String expr, Exception e) {
        if (devMode) {
            LOG.warn("Caught an exception while evaluating expression '{}' against value stack", expr, e);
            LOG.warn("NOTE: Previous warning message was issued due to devMode set to true.");
        } else {
            LOG.debug("Caught an exception while evaluating expression '{}' against value stack", expr, e);
        }
    }

    /**
     * @see org.apache.struts2.util.ValueStack#peek()
     */
    @Override
    public Object peek() {
        return root.peek();
    }

    /**
     * @see org.apache.struts2.util.ValueStack#pop()
     */
    @Override
    public Object pop() {
        return root.pop();
    }

    /**
     * @see org.apache.struts2.util.ValueStack#push(java.lang.Object)
     */
    @Override
    public void push(Object o) {
        root.push(o);
    }

    /**
     * @see org.apache.struts2.util.ValueStack#set(java.lang.String, java.lang.Object)
     */
    @Override
    public void set(String key, Object o) {
        //set basically is backed by a Map pushed on the stack with a key being put on the map and the Object being the value
        Map setMap = retrieveSetMap();
        setMap.put(key, o);
    }

    private Map retrieveSetMap() {
        Map setMap;
        Object topObj = peek();
        if (shouldUseOldMap(topObj)) {
            setMap = (Map) topObj;
        } else {
            setMap = new HashMap();
            setMap.put(MAP_IDENTIFIER_KEY, "");
            push(setMap);
        }
        return setMap;
    }

    /**
     * check if this is a Map put on the stack  for setting if so just use the old map (reduces waste)
     */
    private boolean shouldUseOldMap(Object topObj) {
        return topObj instanceof Map && ((Map) topObj).get(MAP_IDENTIFIER_KEY) != null;
    }

    /**
     * @see org.apache.struts2.util.ValueStack#size()
     */
    @Override
    public int size() {
        return root.size();
    }

    /**
     * Retained for serializability - see {@link org.apache.struts2.ognl.OgnlValueStackTest#testSerializable}
     */
    private Object readResolve() {
        // TODO: this should be done better
        ActionContext ac = ActionContext.getContext();
        Container cont = ac.getContainer();
        XWorkConverter xworkConverter = cont.getInstance(XWorkConverter.class);
        RootAccessor accessor = cont.getInstance(RootAccessor.class);
        TextProvider prov = cont.getInstance(TextProvider.class, "system");
        SecurityMemberAccess sma = cont.getInstance(SecurityMemberAccess.class);
        OgnlValueStack aStack = new OgnlValueStack(xworkConverter, accessor, prov, sma);
        aStack.setOgnlUtil(cont.getInstance(OgnlUtil.class));
        aStack.setRoot(xworkConverter, accessor, this.root, sma);
        return aStack;
    }

    @Override
    public void clearContextValues() {
        //this is an OGNL ValueStack so the context will be an OgnlContext
        //it would be better to make context of type OgnlContext
        ((OgnlContext) context).getValues().clear();
    }

    @Override
    public void useAcceptProperties(Set<Pattern> acceptedProperties) {
        securityMemberAccess.useAcceptProperties(acceptedProperties);
    }

    @Override
    public void useExcludeProperties(Set<Pattern> excludeProperties) {
        securityMemberAccess.useExcludeProperties(excludeProperties);
    }
}
