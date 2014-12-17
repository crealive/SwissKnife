package com.arasthel.swissknife.annotations;

/**
 * Created by Arasthel on 16/12/14.
 */

import groovy.transform.CompileStatic
import org.codehaus.groovy.transform.GroovyASTTransformationClass

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@GroovyASTTransformationClass("com.arasthel.swissknife.annotations.ParcelableTransformation")
@interface Parcelable {
    String[] excludes() default []
}
