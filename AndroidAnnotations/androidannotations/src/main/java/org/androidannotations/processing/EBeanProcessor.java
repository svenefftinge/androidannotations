/**
 * Copyright (C) 2010-2013 eBusiness Information, Excilys Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.androidannotations.processing;

import static com.sun.codemodel.JExpr._new;
import static com.sun.codemodel.JExpr._null;
import static com.sun.codemodel.JMod.FINAL;
import static com.sun.codemodel.JMod.PRIVATE;
import static com.sun.codemodel.JMod.PUBLIC;
import static com.sun.codemodel.JMod.STATIC;
import static org.androidannotations.helper.ModelConstants.GENERATION_SUFFIX;

import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

import org.androidannotations.annotations.EBean;
import org.androidannotations.helper.APTCodeModelHelper;
import org.androidannotations.processing.EBeansHolder.Classes;

import com.sun.codemodel.ClassType;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JVar;

public class EBeanProcessor implements GeneratingElementProcessor {

	public static final String GET_INSTANCE_METHOD_NAME = "getInstance" + GENERATION_SUFFIX;

	@Override
	public String getTarget() {
		return EBean.class.getName();
	}

	@Override
	public void process(Element element, JCodeModel codeModel, EBeansHolder eBeansHolder) throws Exception {

		TypeElement typeElement = (TypeElement) element;

		String eBeanQualifiedName = typeElement.getQualifiedName().toString();

		String generatedBeanQualifiedName = eBeanQualifiedName + GENERATION_SUFFIX;

		JDefinedClass generatedClass = codeModel._class(PUBLIC | FINAL, generatedBeanQualifiedName, ClassType.CLASS);

		EBeanHolder holder = eBeansHolder.create(element, EBean.class, generatedClass);

		JClass eBeanClass = codeModel.directClass(eBeanQualifiedName);

		holder.generatedClass._extends(eBeanClass);

		Classes classes = holder.classes();

		JFieldVar contextField = holder.generatedClass.field(PRIVATE, classes.CONTEXT, "context_");

		holder.contextRef = contextField;

		JMethod init;
		{
			// init
			init = holder.generatedClass.method(PRIVATE, codeModel.VOID, "init_");
			holder.initBody = init.body();
		}

		{
			// init if activity
			/*
			 * We suppress all warnings because we generate an unused warning
			 * that may or may not valid
			 */
			init.annotate(SuppressWarnings.class).param("value", "all");
			APTCodeModelHelper helper = new APTCodeModelHelper();
			holder.initIfActivityBody = helper.ifContextInstanceOfActivity(holder, holder.initBody);
			holder.initActivityRef = helper.castContextToActivity(holder, holder.initIfActivityBody);
		}

		{
			// Constructor

			JMethod constructor = holder.generatedClass.constructor(PRIVATE);

			JVar constructorContextParam = constructor.param(classes.CONTEXT, "context");

			JBlock constructorBody = constructor.body();

			List<ExecutableElement> constructors = ElementFilter.constructorsIn(element.getEnclosedElements());

			ExecutableElement superConstructor = constructors.get(0);

			if (superConstructor.getParameters().size() == 1) {
				constructorBody.invoke("super").arg(constructorContextParam);
			}

			constructorBody.assign(contextField, constructorContextParam);

			constructorBody.invoke(init);
		}

		EBean eBeanAnnotation = element.getAnnotation(EBean.class);
		EBean.Scope eBeanScope = eBeanAnnotation.scope();
		boolean hasSingletonScope = eBeanScope == EBean.Scope.Singleton;

		{
			// Factory method

			JMethod factoryMethod = holder.generatedClass.method(PUBLIC | STATIC, holder.generatedClass, GET_INSTANCE_METHOD_NAME);

			JVar factoryMethodContextParam = factoryMethod.param(classes.CONTEXT, "context");

			JBlock factoryMethodBody = factoryMethod.body();

			/*
			 * Singletons are bound to the application context
			 */
			if (hasSingletonScope) {

				JFieldVar instanceField = holder.generatedClass.field(PRIVATE | STATIC, holder.generatedClass, "instance_");

				JBlock creationBlock = factoryMethodBody //
						._if(instanceField.eq(_null())) //
						._then();
				JVar previousNotifier = holder.replacePreviousNotifierWithNull(creationBlock);
				creationBlock.assign(instanceField, _new(holder.generatedClass).arg(factoryMethodContextParam.invoke("getApplicationContext")));
				holder.resetPreviousNotifier(creationBlock, previousNotifier);

				factoryMethodBody._return(instanceField);
			} else {
				factoryMethodBody._return(_new(holder.generatedClass).arg(factoryMethodContextParam));
			}
		}

		{
			// rebind(Context)
			JMethod rebindMethod = holder.generatedClass.method(PUBLIC, codeModel.VOID, "rebind");
			JVar contextParam = rebindMethod.param(classes.CONTEXT, "context");

			/*
			 * No rebinding of context for singletons, their are bound to the
			 * application context
			 */
			if (!hasSingletonScope) {
				JBlock body = rebindMethod.body();
				body.assign(contextField, contextParam);
				body.invoke(init);
			}
		}

	}
}
