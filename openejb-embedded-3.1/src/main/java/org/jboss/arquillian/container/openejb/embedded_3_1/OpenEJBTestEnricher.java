/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.openejb.embedded_3_1;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingEnumeration;

import org.apache.openejb.assembler.classic.AppInfo;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.prototyping.context.api.ArquillianContext;
import org.jboss.arquillian.prototyping.context.api.Properties;
import org.jboss.arquillian.prototyping.context.api.Property;
import org.jboss.arquillian.prototyping.context.impl.PropertiesImpl;
import org.jboss.arquillian.prototyping.context.impl.openejb.OpenEJBArquillianContextImpl;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * {@link TestEnricher} implementation specific to the OpenEJB
 * Container
 * 
 * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
 * @author <a href="mailto:aslak@conduct.no">Aslak Knutsen</a>
 * @version $Revision: $
 */
public class OpenEJBTestEnricher implements TestEnricher
{
   private static final String ANNOTATION_NAME = "javax.ejb.EJB";

   @org.jboss.arquillian.core.api.annotation.Inject
   private Instance<AppInfo> appInfo;
  
   private ArquillianContext arquillianContext = null;
   
   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.TestEnricher#enrich(org.jboss.arquillian.spi.Context, java.lang.Object)
    */
   public void enrich(Object testCase)
   {
      if(SecurityActions.isClassPresent(ANNOTATION_NAME)) 
      {
         injectClass(testCase);
      }
   }

   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.TestEnricher#resolve(org.jboss.arquillian.spi.Context, java.lang.reflect.Method)
    */
   public Object[] resolve(Method method) 
   {
     return new Object[method.getParameterTypes().length];
   }
   
   /**
    * Obtains all field in the specified class which contain the specified annotation
    * @param clazz
    * @param annotation
    * @return
    * @throws IllegalArgumentException If either argument is not specified
    */
   //TODO Hack, this leaks out privileged operations outside the package.  Extract out properly.
   protected List<Field> getFieldsWithAnnotation(final Class<?> clazz, final Class<? extends Annotation> annotation)
         throws IllegalArgumentException
   {
      // Precondition checks
      if (clazz == null)
      {
         throw new IllegalArgumentException("clazz must be specified");
      }
      if (annotation == null)
      {
         throw new IllegalArgumentException("annotation must be specified");
      }

      // Delegate to the privileged operations
      return SecurityActions.getFieldsWithAnnotation(clazz, annotation);
   }
   
   protected void injectClass(Object testCase) 
   {
      try 
      {
         @SuppressWarnings("unchecked")
         Class<? extends Annotation> ejbAnnotation = (Class<? extends Annotation>)SecurityActions.getThreadContextClassLoader().loadClass(ANNOTATION_NAME);
         
         List<Field> annotatedFields = SecurityActions.getFieldsWithAnnotation(
               testCase.getClass(), 
               ejbAnnotation);
         
         for(Field field : annotatedFields) 
         {
            if(field.get(testCase) == null) // only try to lookup fields that are not already set
            {
               EJB fieldAnnotation = (EJB) field.getAnnotation(ejbAnnotation);
               Object ejb = lookupEJB(field.getType(), fieldAnnotation.mappedName());
               field.set(testCase, ejb);
            }
         }
         
         List<Method> methods = SecurityActions.getMethodsWithAnnotation(
               testCase.getClass(), 
               ejbAnnotation);
         
         for(Method method : methods) 
         {
            if(method.getParameterTypes().length != 1) 
            {
               throw new RuntimeException("@EJB only allowed on single argument methods");
            }
            if(!method.getName().startsWith("set")) 
            {
               throw new RuntimeException("@EJB only allowed on 'set' methods");
            }
            EJB parameterAnnotation = null; //method.getParameterAnnotations()[0]
            for (Annotation annotation : method.getParameterAnnotations()[0])
            {
               if (EJB.class.isAssignableFrom(annotation.annotationType()))
               {
                  parameterAnnotation = (EJB) annotation;
               }
            }
            String mappedName = parameterAnnotation == null ? null : parameterAnnotation.mappedName();
            Object ejb = lookupEJB(method.getParameterTypes()[0], mappedName);
            method.invoke(testCase, ejb);
         }
         
      } 
      catch (Exception e) 
      {
         throw new RuntimeException("Could not inject members", e);
      }
      // Handle Typesafe @Inject (ie. ask Arquillian for a an instance of the field type with no additional context properties)
      final Class<? extends Annotation> inject = (Class<? extends Annotation>) Inject.class;
      List<Field> fieldsWithInject = this.getFieldsWithAnnotation(testCase.getClass(), inject);
      for (final Field field : fieldsWithInject)
      {
         // Set accessible if it's not
         if (!field.isAccessible())
         {
            AccessController.doPrivileged(new PrivilegedAction<Void>()
            {

               public Void run()
               {
                  field.setAccessible(true);

                  // Return
                  return null;
               }
            });
         }
         try
         {
            /*
             *  Resolve (based on contextual properties if specified)
             */
            final Object resolvedVaue;
            final ArquillianContext arquillianContext = this.getArquillianContext();
            final Class<?> type = field.getType();

            // If Properties are defined
            if (field.isAnnotationPresent(Properties.class))
            {
               final Properties properties = field.getAnnotation(Properties.class);
               resolvedVaue = arquillianContext.get(type, properties);
            }
            // If just one property is defined
            else if (field.isAnnotationPresent(Property.class))
            {
               final Property property = field.getAnnotation(Property.class);
               final Properties properties = new PropertiesImpl(new Property[]
               {property});
               resolvedVaue = arquillianContext.get(type, properties);
            }
            // No properties defined; do type-based resolution only
            else
            {
               resolvedVaue = arquillianContext.get(type);
            }

            // Inject
            field.set(testCase, resolvedVaue);
         }
         catch (final IllegalAccessException e)
         {
            throw new RuntimeException("Could not inject into " + field.getName() + " of test case: " + testCase, e);
         }
      }
   }
   
   protected Context createContext() throws Exception
   {
      return this.getArquillianContext().get(InitialContext.class);
   }
   
   protected ArquillianContext getArquillianContext()
   {
      if (arquillianContext == null)
      {
         // Make a context
         final AppInfo deployment = appInfo.get();
         arquillianContext = new OpenEJBArquillianContextImpl(deployment);
      }
      return arquillianContext;
   }

   protected Object lookupEJB(Class<?> fieldType, String mappedName) throws Exception
   {
      Context initcontext = createContext();
      if(mappedName != null && !mappedName.equals(""))
      {
         return initcontext.lookup(mappedName);
      }
      return lookupRecursive(fieldType, initcontext, initcontext.listBindings("/"));
   }

   //TODO No, no no: we must look up a known location from metadata, not search for a matching type in the whole JNDI tree
   protected Object lookupRecursive(Class<?> fieldType, Context context,
         NamingEnumeration<Binding> contextNames) throws Exception
   {
      while (contextNames.hasMore())
      {
         Binding contextName = contextNames.nextElement();
         Object value = contextName.getObject();
         if (Context.class.isInstance(value))
         {
            Context subContext = (Context) value;
            return lookupRecursive(fieldType, subContext, subContext.listBindings("/"));
         }
         else
         {
            value = context.lookup(contextName.getName());
            if (fieldType.isInstance(value))
            {
               return value;
            }
         }
      }
      throw new RuntimeException("Could not lookup EJB reference for: " + fieldType);
   }
}
