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
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.resource.spi.ResourceAdapter;
import javax.sql.DataSource;
import javax.transaction.UserTransaction;

import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * {@link TestEnricher} implementation specific to the OpenEJB
 * Container for injecting <code>@Resource</code> annotated
 * fields and method parameters.
 * 
 * @author David Allen
 *
 */
public class OpenEJBResourceInjectionEnricher implements TestEnricher
{

   private static final String RESOURCE_LOOKUP_PREFIX = "java:/comp/env";
   private static final String ANNOTATION_NAME = "javax.annotation.Resource";
   
   
   private static final Logger log = Logger.getLogger(OpenEJBResourceInjectionEnricher.class.getName());
   
   @Inject
   private Instance<Context> contextInst;
   
   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.TestEnricher#enrich(org.jboss.arquillian.spi.Context, java.lang.Object)
    */
   public void enrich(Object testCase)
   {
      if(SecurityActions.isClassPresent(ANNOTATION_NAME) && contextInst.get() != null) 
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

   protected void injectClass(Object testCase) 
   {
      try 
      {
         @SuppressWarnings("unchecked")
         Class<? extends Annotation> resourceAnnotation = (Class<? extends Annotation>)SecurityActions.getThreadContextClassLoader().loadClass(ANNOTATION_NAME);
         
         List<Field> annotatedFields = SecurityActions.getFieldsWithAnnotation(
               testCase.getClass(), 
               resourceAnnotation);
         
         for(Field field : annotatedFields) 
         {
            /*
             * only try to lookup fields that are not already set or primitives
             * (we don't really know if they have been set or not)
             */
            Object currentValue = field.get(testCase);
            if(shouldInject(field, currentValue))
            {
               Object resource = resolveResource(field);
               field.set(testCase, resource);
            }
         }
         
         List<Method> methods = SecurityActions.getMethodsWithAnnotation(
               testCase.getClass(), 
               resourceAnnotation);
         
         for(Method method : methods) 
         {
            if(method.getParameterTypes().length != 1) 
            {
               throw new RuntimeException("@Resource only allowed on single argument methods");
            }
            if(!method.getName().startsWith("set")) 
            {
               throw new RuntimeException("@Resource only allowed on 'set' methods");
            }
            Object resource = resolveResource(method);
            method.invoke(testCase, resource);
         }
      } 
      catch (Exception e) 
      {
         throw new RuntimeException("Could not inject members", e);
      }
   }

   private boolean shouldInject(Field field, Object currentValue)
   {
      Class<?> type = field.getType();
      if(type.isPrimitive())
      {
         if(isPrimitiveNull(currentValue)) 
         {
            log.fine("Primitive field " + field.getName() + " has been detected to have the default primitive value, " +
                    "can not determine if it has already been injected. Re-injecting field.");
            return true;
         }
      }
      else
      {
         if(currentValue == null)
         {
            return true;
         }
      }
      return false;
   }
   
   private boolean isPrimitiveNull(Object currentValue)
   {
      String stringValue = String.valueOf(currentValue);
      if("0".equals(stringValue) || "0.0".equals(stringValue) || "false".equals(stringValue))
      {
         return true;
      } 
      else if(Character.class.isInstance(currentValue))
      {
         if( Character.class.cast(currentValue) == (char)0) 
         {
            return true;
         }
      }
      return false;
   }

   protected Object lookup(String jndiName) throws Exception 
   {
      // TODO: figure out test context ? 
      Context context = getContainerContext();
      return context.lookup(jndiName);
   }
   
   /**
    * Obtains the appropriate context for the test.  Can be overriden by
    * enrichers for each container to provide the correct context.
    * @return the test context
    * @throws NamingException
    */
   protected Context getContainerContext() throws NamingException
   {
      return contextInst.get();
   }

   protected String getResourceName(Field field)
   {
      Resource resource = field.getAnnotation(Resource.class);
      String resourceName = getResourceName(resource);
      if(resourceName != null) 
      {
       return resourceName;
      }
      String propertyName = field.getName();
      String className = field.getDeclaringClass().getName();
      return RESOURCE_LOOKUP_PREFIX + "/" + className + "/" + propertyName;
   }
   
   protected String getResourceName(Resource resource)
   {
      String mappedName = resource.mappedName();
      if (!mappedName.equals(""))
      {
         return mappedName;
      }
      String name = resource.name();
      if (!name.equals(""))
      {
         return RESOURCE_LOOKUP_PREFIX + "/" + name;
      }
      return null;
   }

   
   private static final String RESOURCE_ADAPTER_LOOKUP_PREFIX = "openejb/Resource";

   protected Object resolveResource(AnnotatedElement element) throws Exception
   {
      Object resolvedResource = null;
      Class<?> resourceType = null;
      if (Field.class.isAssignableFrom(element.getClass()))
      {
         resourceType = ((Field) element).getType();
      }
      else if (Method.class.isAssignableFrom(element.getClass()))
      {
         resourceType = ((Method) element).getParameterTypes()[0];
      }
      if (resourceType == null)
      {
         throw new IllegalStateException("No type found for resource injection target " + element);
      }

      // If the element type is a resource adapter, then apply special rules
      // for looking it up in JNDI
      if (ResourceAdapter.class.isAssignableFrom(resourceType)
            || DataSource.class.isAssignableFrom(resourceType))
      {
         Resource resourceAnnotation = element.getAnnotation(Resource.class);
         if (!resourceAnnotation.name().equals(""))
         {
            resolvedResource = lookup(RESOURCE_ADAPTER_LOOKUP_PREFIX + "/" + resourceAnnotation.name());
         }
         else if (!resourceAnnotation.mappedName().equals(""))
         {
            resolvedResource = lookup(resourceAnnotation.mappedName());
         }
         else
         {
            resolvedResource = findResourceByType(resourceType);
         }
      }
      else if (UserTransaction.class.isAssignableFrom(resourceType))
      {
         resolvedResource = lookup("java:comp/UserTransaction");
      }
      
      return resolvedResource;
   }

   private Object findResourceByType(Class<?> resourceType) throws NamingException
   {
      NamingEnumeration<Binding> namingEnumeration = null;
      try 
      {
         namingEnumeration = getContainerContext().listBindings(RESOURCE_ADAPTER_LOOKUP_PREFIX);
      }
      catch (NamingException ignore)
      {
         // No resource adapters exist, so we don't find anything here
      }
      List<Object> resourceMatches = new ArrayList<Object>();
      while ((namingEnumeration != null) && (namingEnumeration.hasMoreElements()))
      {
         Binding binding = namingEnumeration.next();
         Object boundResource = binding.getObject();
         if (resourceType.isAssignableFrom(boundResource.getClass()))
         {
            resourceMatches.add(boundResource);
         }
      }
      if (resourceMatches.size() == 1)
      {
         return resourceMatches.get(0);
      }
      else if (resourceMatches.size() > 1)
      {
         // Throw some ambiguous matches exception perhaps?
         return resourceMatches.get(0);
      }
      throw new RuntimeException("Could not inject resource of type " + resourceType);
   }

}
