/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.gen;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.AbstractTypeDeclaration;
import com.google.devtools.j2objc.ast.Annotation;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.BodyDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.FieldDeclaration;
import com.google.devtools.j2objc.ast.FunctionDeclaration;
import com.google.devtools.j2objc.ast.Javadoc;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.Name;
import com.google.devtools.j2objc.ast.NativeDeclaration;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.TagElement;
import com.google.devtools.j2objc.ast.TextElement;
import com.google.devtools.j2objc.ast.TreeNode;
import com.google.devtools.j2objc.ast.TreeNode.Kind;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.text.BreakIterator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Generates source files from AST types.  This class handles common actions
 * shared by the header and implementation generators.
 *
 * @author Tom Ball
 */
public abstract class ObjectiveCSourceFileGenerator extends SourceFileGenerator {

  /**
   * Create a new generator.
   *
   * @param unit The AST of the source to generate
   * @param emitLineDirectives if true, generate CPP line directives
   */
  protected ObjectiveCSourceFileGenerator(GenerationUnit unit, boolean emitLineDirectives) {
    super(unit, emitLineDirectives);
  }

  /**
   * Generate an output source file from the specified type declaration.
   */
  public void generate(AbstractTypeDeclaration node) {
    if (node instanceof TypeDeclaration) {
      generate((TypeDeclaration) node);
    } else if (node instanceof EnumDeclaration) {
      generate((EnumDeclaration) node);
    } else if (node instanceof AnnotationTypeDeclaration) {
      generate((AnnotationTypeDeclaration) node);
    }
  }

  protected abstract void generate(TypeDeclaration node);

  protected abstract void generate(EnumDeclaration node);

  protected abstract void generate(AnnotationTypeDeclaration node);

  private static final Function<VariableDeclaration, IVariableBinding> GET_VARIABLE_BINDING_FUNC =
      new Function<VariableDeclaration, IVariableBinding>() {
    public IVariableBinding apply(VariableDeclaration node) {
      return node.getVariableBinding();
    }
  };

  private static final Predicate<VariableDeclaration> IS_STATIC_VARIABLE_PRED =
      new Predicate<VariableDeclaration>() {
    public boolean apply(VariableDeclaration node) {
      return BindingUtil.isStatic(node.getVariableBinding());
    }
  };

  private static final Predicate<VariableDeclarationFragment> NEEDS_INITIALIZATION_PRED =
      new Predicate<VariableDeclarationFragment>() {
    public boolean apply(VariableDeclarationFragment frag) {
      IVariableBinding binding = frag.getVariableBinding();
      return BindingUtil.isStatic(binding) && !BindingUtil.isPrimitiveConstant(binding);
    }
  };

  protected static final String DEPRECATED_ATTRIBUTE = "__attribute__((deprecated))";

  protected Iterable<IVariableBinding> getStaticFieldsNeedingAccessors(
      AbstractTypeDeclaration node) {
    return Iterables.transform(
        Iterables.filter(TreeUtil.getAllFields(node), IS_STATIC_VARIABLE_PRED),
        GET_VARIABLE_BINDING_FUNC);
  }

  /**
   * Excludes primitive constants which will not have variables declared for them.
   */
  protected Iterable<VariableDeclarationFragment> getStaticFieldsNeedingInitialization(
      AbstractTypeDeclaration node) {
    return Iterables.filter(TreeUtil.getAllFields(node), NEEDS_INITIALIZATION_PRED);
  }

  protected boolean hasInitializeMethod(AbstractTypeDeclaration node) {
    return !node.getClassInitStatements().isEmpty();
  }

  protected void printMethod(MethodDeclaration m) {
    if (m.isConstructor()) {
      printConstructor(m);
    } else {
      printNormalMethod(m);
    }
  }

  protected abstract void printFunction(FunctionDeclaration declaration);

  protected abstract void printNativeDeclaration(NativeDeclaration declaration);

  private void printDeclaration(BodyDeclaration declaration) {
    switch (declaration.getKind()) {
      case METHOD_DECLARATION:
        printMethod((MethodDeclaration) declaration);
        return;
      case NATIVE_DECLARATION:
        printNativeDeclaration((NativeDeclaration) declaration);
        return;
      default:
        break;
    }
  }

  protected void printDeclarations(Iterable<BodyDeclaration> declarations) {
    for (BodyDeclaration declaration : declarations) {
      printDeclaration(declaration);
    }
  }

  protected void printFunctions(Iterable<BodyDeclaration> declarations) {
    for (BodyDeclaration declaration : declarations) {
      if (declaration.getKind() == Kind.FUNCTION_DECLARATION) {
        printFunction((FunctionDeclaration) declaration);
      }
    }
  }

  protected abstract void printNormalMethod(MethodDeclaration m);

  protected abstract void printConstructor(MethodDeclaration m);

  /**
   * Create an Objective-C method declaration string.
   */
  protected String methodDeclaration(MethodDeclaration m) {
    assert !m.isConstructor();
    return constructMethodDeclaration(m, NameTable.getMethodSelector(m.getMethodBinding()));
  }

  private String constructMethodDeclaration(MethodDeclaration m, String selector) {
    StringBuilder sb = new StringBuilder();
    IMethodBinding binding = m.getMethodBinding();
    char prefix = Modifier.isStatic(m.getModifiers()) ? '+' : '-';
    String returnType = NameTable.getObjCType(binding.getReturnType());
    if (m.isConstructor()) {
      returnType = "instancetype";
    } else if (selector.equals("hash")) {
      // Explicitly test hashCode() because of NSObject's hash return value.
      returnType = "NSUInteger";
    }
    sb.append(String.format("%c (%s)", prefix, returnType));

    List<SingleVariableDeclaration> params = m.getParameters();
    String[] selParts = selector.split(":");

    if (params.isEmpty()) {
      assert selParts.length == 1 && !selector.endsWith(":");
      sb.append(selParts[0]);
    } else {
      assert params.size() == selParts.length;
      int baseLength = sb.length() + selParts[0].length();
      for (int i = 0; i < params.size(); i++) {
        if (i != 0) {
          sb.append('\n');
          sb.append(pad(baseLength - selParts[i].length()));
        }
        IVariableBinding var = params.get(i).getVariableBinding();
        String typeName = NameTable.getSpecificObjCType(var.getType());
        sb.append(String.format("%s:(%s)%s", selParts[i], typeName, NameTable.getName(var)));
      }
    }

    return sb.toString();
  }

  /**
   * Create an Objective-C constructor declaration string.
   */
  protected String constructorDeclaration(MethodDeclaration m) {
    return constructorDeclaration(m, /* isInner */ false);
  }

  protected String constructorDeclaration(MethodDeclaration m, boolean isInner) {
    assert m.isConstructor();
    String selector = NameTable.getMethodSelector(m.getMethodBinding());
    if (isInner) {
      selector = "init" + NameTable.getFullName(m.getMethodBinding().getDeclaringClass())
          + selector.substring(4);
    }
    return constructMethodDeclaration(m, selector);
  }

  /**
   * Create an Objective-C constructor from a list of annotation member
   * declarations.
   */
  protected String annotationConstructorDeclaration(ITypeBinding annotation) {
    StringBuffer sb = new StringBuffer();
    sb.append("- (instancetype)init");
    IMethodBinding[] members = BindingUtil.getSortedAnnotationMembers(annotation);
    for (int i = 0; i < members.length; i++) {
      if (i == 0) {
        sb.append("With");
      } else {
        sb.append(" with");
      }
      IMethodBinding member = members[i];
      String name = NameTable.getAnnotationPropertyName(member);
      sb.append(NameTable.capitalize(name));
      sb.append(":(");
      sb.append(NameTable.getSpecificObjCType(member.getReturnType()));
      sb.append(')');
      sb.append(name);
      sb.append("__");
    }
    return sb.toString();
  }

  /** Ignores deprecation warnings. Deprecation warnings should be visible for human authored code,
   *  not transpiled code. This method should be paired with popIgnoreDeprecatedDeclarationsPragma.
   */
  protected void pushIgnoreDeprecatedDeclarationsPragma() {
    if (Options.generateDeprecatedDeclarations()) {
      printf("#pragma clang diagnostic push\n");
      printf("#pragma GCC diagnostic ignored \"-Wdeprecated-declarations\"\n");
    }
  }

  /** Restores deprecation warnings after a call to pushIgnoreDeprecatedDeclarationsPragma. */
  protected void popIgnoreDeprecatedDeclarationsPragma() {
    if (Options.generateDeprecatedDeclarations()) {
      printf("#pragma clang diagnostic pop\n");
    }
  }

  protected void printDocComment(Javadoc javadoc) {
    if (javadoc != null) {
      printIndent();
      println("/**");
      List<TagElement> tags = javadoc.getTags();
      for (TagElement tag : tags) {

        if (tag.getTagName() == null) {
          // Description section.
          String description = printTagFragments(tag.getFragments());

          // Extract first sentence from description.
          BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
          iterator.setText(description.toString());
          int start = iterator.first();
          int end = iterator.next();
          if (end != BreakIterator.DONE) {
            // Print brief tag first, since Quick Help shows it first. This makes the
            // generated source easier to review.
            printDocLine(String.format("@brief %s", description.substring(start, end).trim()));
            String remainder = description.substring(end).trim();
            if (!remainder.isEmpty()) {
              printDocLine(remainder);
            }
          } else {
            printDocLine(description.trim());
          }
        } else {
          String doc = printJavadocTag(tag);
          if (!doc.isEmpty()) {
            printDocLine(doc);
          }
        }
      }
      printIndent();
      println(" */");
    }
  }

  private void printDocLine(String line) {
    printIndent();
    print(' ');
    println(line);
  }

  private String printJavadocTag(TagElement tag) {
    String tagName = tag.getTagName();
    // Xcode 5 compatible tags.
    if (tagName.equals(TagElement.TAG_AUTHOR)
        || tagName.equals(TagElement.TAG_EXCEPTION)
        || tagName.equals(TagElement.TAG_PARAM)
        || tagName.equals(TagElement.TAG_RETURN)
        || tagName.equals(TagElement.TAG_SINCE)
        || tagName.equals(TagElement.TAG_THROWS)
        || tagName.equals(TagElement.TAG_VERSION)) {
      return String.format("%s %s", tagName, printTagFragments(tag.getFragments()));
    }

    if (tagName.equals(TagElement.TAG_DEPRECATED)) {
      // Deprecated annotation translated instead.
      return "";
    }

    if (tagName.equals(TagElement.TAG_SEE)) {
      // TODO(tball): implement @see when Xcode quick help links are documented.
      return "";
    }

    if (tagName.equals(TagElement.TAG_CODE)) {
      return String.format("<code>%s</code>", printTagFragments(tag.getFragments()));
    }

    // Remove tag, but return any text it has.
    return printTagFragments(tag.getFragments());
  }

  private String printTagFragments(List<TreeNode> fragments) {
    StringBuilder sb = new StringBuilder();
    for (TreeNode fragment : fragments) {
      sb.append(' ');
      if (fragment instanceof TextElement) {
        String text = escapeDocText(((TextElement) fragment).getText());
        sb.append(text.trim());
      } else if (fragment instanceof TagElement) {
        sb.append(printJavadocTag((TagElement) fragment));
      } else {
        sb.append(escapeDocText(fragment.toString()).trim());
      }
    }
    return sb.toString().trim();
  }

  private String escapeDocText(String text) {
    return text.replace("@", "@@").replace("/*", "/\\*");
  }

  /**
   * Prints the list of instance variables in a type.
   *
   * @param node the type to examine
   * @param privateVars if true, only print private vars, otherwise print all but private vars
   */
  protected void printInstanceVariables(AbstractTypeDeclaration node, boolean privateVars) {
    indent();
    boolean first = true;
    boolean printAllVars = !Options.hidePrivateMembers() && !privateVars;
    for (FieldDeclaration field : TreeUtil.getFieldDeclarations(node)) {
      int modifiers = field.getModifiers();
      if (!Modifier.isStatic(field.getModifiers())
          && (printAllVars || (privateVars == isPrivateOrSynthetic(modifiers)))) {
        List<VariableDeclarationFragment> vars = field.getFragments();
        assert !vars.isEmpty();
        IVariableBinding varBinding = vars.get(0).getVariableBinding();
        ITypeBinding varType = varBinding.getType();
        // Need direct access to fields possibly from inner classes that are
        // promoted to top level classes, so must make all visible fields public.
        if (first) {
          println(" @public");
          first = false;
        }
        printDocComment(field.getJavadoc());
        printIndent();
        if (BindingUtil.isWeakReference(varBinding)) {
          // We must add this even without -use-arc because the header may be
          // included by a file compiled with ARC.
          print("__weak ");
        }
        String objcType = NameTable.getSpecificObjCType(varType);
        boolean needsAsterisk = !varType.isPrimitive() && !objcType.matches("id|id<.*>|Class");
        if (needsAsterisk && objcType.endsWith(" *")) {
          // Strip pointer from type, as it will be added when appending fragment.
          // This is necessary to create "Foo *one, *two;" declarations.
          objcType = objcType.substring(0, objcType.length() - 2);
        }
        print(objcType);
        print(' ');
        for (Iterator<VariableDeclarationFragment> it = field.getFragments().iterator();
             it.hasNext(); ) {
          VariableDeclarationFragment f = it.next();
          if (needsAsterisk) {
            print('*');
          }
          String name = NameTable.getName(f.getName().getBinding());
          print(NameTable.javaFieldToObjC(name));
          if (it.hasNext()) {
            print(", ");
          }
        }
        println(";");
      }
    }
    unindent();
  }

  protected boolean isPrivateOrSynthetic(int modifiers) {
    return Modifier.isPrivate(modifiers) || BindingUtil.isSynthetic(modifiers);
  }

  protected void printNormalMethodDeclaration(MethodDeclaration m) {
    newline();
    printDocComment(m.getJavadoc());
    print(this.methodDeclaration(m));
    String methodName = NameTable.getMethodSelector(m.getMethodBinding());
    if (!BindingUtil.isSynthetic(m.getModifiers())
        && needsObjcMethodFamilyNoneAttribute(methodName)) {
         // Getting around a clang warning.
         // clang assumes that methods with names starting with new, alloc or copy
         // return objects of the same type as the receiving class, regardless of
         // the actual declared return type. This attribute tells clang to not do
         // that, please.
         // See http://clang.llvm.org/docs/AutomaticReferenceCounting.html
         // Sections 5.1 (Explicit method family control)
         // and 5.2.2 (Related result types)
         print(" OBJC_METHOD_FAMILY_NONE");
       }

    if (needsDeprecatedAttribute(m.getAnnotations())) {
      print(" " + DEPRECATED_ATTRIBUTE);
    }
    println(";");
  }

  protected boolean needsObjcMethodFamilyNoneAttribute(String name) {
    return name.startsWith("new") || name.startsWith("copy") || name.startsWith("alloc")
        || name.startsWith("init") || name.startsWith("mutableCopy");
  }

  protected boolean needsDeprecatedAttribute(List<Annotation> annotations) {
    return Options.generateDeprecatedDeclarations() && hasDeprecated(annotations);
  }

  private boolean hasDeprecated(List<Annotation> annotations) {
    for (Annotation annotation : annotations) {
      Name annotationTypeName = annotation.getTypeName();
      String expectedTypeName =
          annotationTypeName.isQualifiedName() ? "java.lang.Deprecated" : "Deprecated";
      if (expectedTypeName.equals(annotationTypeName.getFullyQualifiedName())) {
        return true;
      }
    }

    return false;
  }

  protected void printFieldSetters(AbstractTypeDeclaration node, boolean privateVars) {
    ITypeBinding declaringType = node.getTypeBinding();
    boolean newlinePrinted = false;
    boolean printAllVars = !Options.hidePrivateMembers() && !privateVars;
    for (FieldDeclaration field : TreeUtil.getFieldDeclarations(node)) {
      ITypeBinding type = field.getType().getTypeBinding();
      int modifiers = field.getModifiers();
      if (Modifier.isStatic(modifiers) || type.isPrimitive()
          || (!printAllVars && isPrivateOrSynthetic(modifiers) != privateVars)) {
        continue;
      }
      String typeStr = NameTable.getObjCType(type);
      String declaringClassName = NameTable.getFullName(declaringType);
      for (VariableDeclarationFragment var : field.getFragments()) {
        if (BindingUtil.isWeakReference(var.getVariableBinding())) {
          continue;
        }
        String fieldName = NameTable.javaFieldToObjC(NameTable.getName(var.getName().getBinding()));
        if (!newlinePrinted) {
          newlinePrinted = true;
          newline();
        }
        println(String.format("J2OBJC_FIELD_SETTER(%s, %s, %s)",
            declaringClassName, fieldName, typeStr));
      }
    }
  }

  protected String getFunctionSignature(FunctionDeclaration function) {
    StringBuilder sb = new StringBuilder();
    String returnType = NameTable.getObjCType(function.getReturnType().getTypeBinding());
    returnType += returnType.endsWith("*") ? "" : " ";
    sb.append(returnType).append(function.getName()).append('(');
    for (Iterator<SingleVariableDeclaration> iter = function.getParameters().iterator();
         iter.hasNext(); ) {
      IVariableBinding var = iter.next().getVariableBinding();
      String paramType = NameTable.getSpecificObjCType(var.getType());
      paramType += (paramType.endsWith("*") ? "" : " ");
      sb.append(paramType + NameTable.getName(var));
      if (iter.hasNext()) {
        sb.append(", ");
      }
    }
    sb.append(')');
    return sb.toString();
  }
}
