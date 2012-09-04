package com.profiler.interceptor.bci;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.NotFoundException;

import com.profiler.interceptor.Interceptor;
import com.profiler.interceptor.InterceptorRegistry;
import com.profiler.interceptor.LoggingInterceptor;
import com.profiler.interceptor.StaticAfterInterceptor;
import com.profiler.interceptor.StaticAroundInterceptor;
import com.profiler.interceptor.StaticBeforeInterceptor;

public class JavaAssistClass implements InstrumentClass {

	private final Logger logger = Logger.getLogger(this.getClass().getName());

	private JavaAssistByteCodeInstrumentor instrumentor;
	private CtClass ctClass;

	public JavaAssistClass(JavaAssistByteCodeInstrumentor instrumentor, CtClass ctClass) {
		this.instrumentor = instrumentor;
		this.ctClass = ctClass;
	}

    @Override
	public boolean addInterceptor(String methodName, String[] args, Interceptor interceptor) {
        return addInterceptor(methodName, args, interceptor, Type.auto);
	}

	@Override
	public boolean addInterceptor(String methodName, String[] args, Interceptor interceptor, Type type) {
		if (interceptor == null)
			return false;

		CtMethod method = getMethod(methodName, args);
        if(method == null) {
            return false;
        }

		int id = InterceptorRegistry.addInterceptor(interceptor);
		try {
            if(type == Type.auto) {
                if (interceptor instanceof StaticAroundInterceptor) {
                    addStaticAroundInterceptor(methodName, id, method);
                } else if (interceptor instanceof StaticBeforeInterceptor) {
                    addStaticBeforeInterceptor(methodName, id, method);
                } else if (interceptor instanceof StaticAfterInterceptor) {
                    addStaticAfterInterceptor(methodName, id, method);
                } else {
                    return false;
                }
            } else if(type == Type.around && interceptor instanceof  StaticAroundInterceptor) {
                addStaticAroundInterceptor(methodName, id, method);
            } else if(type == Type.before && interceptor instanceof StaticBeforeInterceptor) {
                addStaticBeforeInterceptor(methodName, id, method);
            } else if(type == Type.after && interceptor instanceof StaticAfterInterceptor) {
                addStaticAfterInterceptor(methodName, id, method);
            } else {
                return false;
            }
			return true;
		} catch (NotFoundException e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		} catch (CannotCompileException e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		}
		return false;
	}

	private void addStaticAroundInterceptor(String methodName, int id, CtBehavior method) throws NotFoundException, CannotCompileException {
		addStaticBeforeInterceptor(methodName, id, method);
		addStaticAfterInterceptor(methodName, id, method);
	}

	private void addStaticAfterInterceptor(String methodName, int id, CtBehavior behavior) throws NotFoundException, CannotCompileException {
		StringBuilder after = new StringBuilder(1024);
		after.append("{");
		addGetStaticAfterInterceptor(after, id);
        String target = getTarget(behavior);
        after.append("  interceptor.after(" + target + ", \"" + ctClass.getName() + "\", \"" + methodName + "\", $args, ($w)$_);");
		after.append("}");
		String buildAfter = after.toString();
		if (logger.isLoggable(Level.INFO)) {
			logger.info("addStaticAfterInterceptor after behavior:" + behavior.getLongName() + " code:" + buildAfter);
		}
		behavior.insertAfter(buildAfter);

		StringBuilder catchCode = new StringBuilder(1024);
		catchCode.append("{");
		addGetStaticAfterInterceptor(catchCode, id);
		catchCode.append("  interceptor.after(" + target + ", \"" + ctClass.getName() + "\", \"" + methodName + "\", $args, $e);");
		catchCode.append("  throw $e;");
		catchCode.append("}");
		String buildCatch = catchCode.toString();
		if (logger.isLoggable(Level.INFO)) {
			logger.info("addStaticAfterInterceptor catch behavior:" + behavior.getLongName() + " code:" + buildCatch);
		}
		CtClass th = instrumentor.getClassPool().get("java.lang.Throwable");
		behavior.addCatch(buildCatch, th);

	}

    private String getTarget(CtBehavior behavior) {
        boolean staticMethod = isStatic(behavior);
        if(staticMethod) {
            return "null";
        } else {
            return "this";
        }
    }

    private boolean isStatic(CtBehavior behavior) {
        int modifiers = behavior.getModifiers();
        return java.lang.reflect.Modifier.isStatic(modifiers);
    }

    private void addGetStaticAfterInterceptor(StringBuilder after, int id) {
		after.append("  com.profiler.interceptor.StaticAfterInterceptor interceptor = " + "(com.profiler.interceptor.StaticAfterInterceptor) com.profiler.interceptor.InterceptorRegistry.getInterceptor(");
		after.append(id);
		after.append(");");
	}

	private void addStaticBeforeInterceptor(String methodName, int id, CtBehavior behavior) throws CannotCompileException {
		StringBuilder code = new StringBuilder(1024);
		code.append("{");
		addGetBeforeInterceptor(id, code);
        String target = getTarget(behavior);
        code.append("  interceptor.before(" + target + ", \"" + ctClass.getName() + "\", \"" + methodName + "\", $args);");
		code.append("}");
		String buildBefore = code.toString();
		if (logger.isLoggable(Level.INFO)) {
			logger.info("addStaticBeforeInterceptor catch behavior:" + behavior.getLongName() + " code:" + buildBefore);
		}

		if (behavior instanceof CtConstructor) {
			((CtConstructor) behavior).insertBeforeBody(buildBefore);
		} else {
			behavior.insertBefore(buildBefore);
		}
	}

	private void addGetBeforeInterceptor(int id, StringBuilder code) {
		code.append("  com.profiler.interceptor.StaticBeforeInterceptor interceptor = " + "(com.profiler.interceptor.StaticBeforeInterceptor)com.profiler.interceptor.InterceptorRegistry.getInterceptor(");
		code.append(id);
		code.append(");");
	}

	public boolean addDebugLogBeforeAfterMethod() {
		String className = this.ctClass.getName();
		LoggingInterceptor loggingInterceptor = new LoggingInterceptor(className);
		int id = InterceptorRegistry.addInterceptor(loggingInterceptor);
		try {
			CtClass cc = this.instrumentor.getClassPool().get(className);
			CtMethod[] methods = cc.getDeclaredMethods();

			for (CtMethod method : methods) {
				if (method.isEmpty()) {
					if (logger.isLoggable(Level.FINE)) {
						logger.fine(method.getLongName() + " is empty.");
					}
					continue;
				}
				String methodName = method.getName();

				// TODO method의 prameter type을 interceptor에 별도 추가해야 될것으로 보임.
				String params = getParamsToString(method.getParameterTypes());
				addStaticAroundInterceptor(methodName, id, method);
			}
			return true;
		} catch (Exception e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		}
		return false;
	}

	/**
	 * 제대로 동작안함 다시 봐야 될것 같음. 생성자일경우의 bytecode 수정시 에러가 남.
	 * 
	 * @return
	 */
	@Deprecated
	public boolean addDebugLogBeforeAfterConstructor() {
		String className = this.ctClass.getName();
		LoggingInterceptor loggingInterceptor = new LoggingInterceptor(className);
		int id = InterceptorRegistry.addInterceptor(loggingInterceptor);
		try {
			CtClass cc = this.instrumentor.getClassPool().get(className);
			CtConstructor[] constructors = cc.getConstructors();

			for (CtConstructor constructor : constructors) {
				if (constructor.isEmpty()) {
					if (logger.isLoggable(Level.FINE)) {
						logger.fine(constructor.getLongName() + " is empty.");
					}
					continue;
				}
				String constructorName = constructor.getName();
				String params = getParamsToString(constructor.getParameterTypes());

				// constructor.insertAfter("{System.out.println(\"*****" +
				// constructorName + " Constructor:Param=(" + params +
				// ") is finished. \" + $args);}");
				// constructor.addCatch("{System.out.println(\"*****" +
				// constructorName + " Constructor:Param=(" + params +
				// ") is finished.\"); throw $e; }"
				// , instrumentor.getClassPool().get("java.lang.Throwable"));
				addStaticAroundInterceptor(constructorName, id, constructor);
			}
			return true;
		} catch (Exception e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		}
		return false;
	}

	private String getParamsToString(CtClass[] params) throws NotFoundException {
		StringBuilder sb = new StringBuilder(512);
		if (params.length != 0) {
			int paramsLength = params.length;
			for (int loop = paramsLength - 1; loop > 0; loop--) {
				sb.append(params[loop].getName()).append(",");
			}
		}
		String paramsStr = sb.toString();
		if (logger.isLoggable(Level.FINE)) {
			logger.fine("params type:" + paramsStr);
		}
		return paramsStr;
	}

	private CtMethod getMethod(String methodName, String[] args) {
		try {
            CtClass[] params = getCtParameter(args);
            return ctClass.getDeclaredMethod(methodName, params);
        } catch (NotFoundException e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage(), e);
			}
        }
        return null;
    }

	private CtClass[] getCtParameter(String[] args) throws NotFoundException {
		if (args == null) {
			return null;
		}
		CtClass[] params = new CtClass[args.length];
		for (int i = 0; i < args.length; i++) {
			params[i] = instrumentor.getClassPool().getCtClass(args[i]);
		}
		return params;
	}

	@Override
	public byte[] toBytecode() {
		try {
			return ctClass.toBytecode();
		} catch (IOException e) {
			logger.log(Level.INFO, "IoException class:" + ctClass.getName() + " " + e.getMessage(), e);
		} catch (CannotCompileException e) {
			logger.log(Level.INFO, "CannotCompileException class:" + ctClass.getName() + " " + e.getMessage(), e);
		}
		return null;
	}

	public Class<?> toClass() {
		try {
			return ctClass.toClass();
		} catch (CannotCompileException e) {
			logger.log(Level.INFO, "CannotCompileException class:" + ctClass.getName() + " " + e.getMessage(), e);
		}
		return null;
	}
}
