/*
 * Copyright © 2010 Reinier Zwitserloot and Roel Spilker.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.ast.grammar;

import lombok.ast.Node;

import org.parboiled.Action;
import org.parboiled.BaseParser;
import org.parboiled.Context;
import org.parboiled.Rule;
import org.parboiled.support.Cached;

public class ExpressionsParser extends BaseParser<Node> {
	final ParserGroup group;
	final ExpressionsActions actions;
	
	public ExpressionsParser(ParserGroup group) {
		this.actions = new ExpressionsActions(group.getSource());
		this.group = group;
	}
	
	/**
	 * P0
	 */
	public Rule primaryExpression() {
		return firstOf(
				parenGrouping(),
				group.literals.anyLiteral(),
				unqualifiedThisOrSuperLiteral(),
				arrayCreationExpression(),
				unqualifiedConstructorInvocation(),
				qualifiedClassOrThisOrSuperLiteral(),
				identifierExpression());
	}
	
	Rule parenGrouping() {
		return sequence(
				ch('('), group.basics.optWS(),
				anyExpression(), SET(),
				ch(')'), SET(actions.addParens(VALUE())),
				group.basics.optWS());
	}
	
	Rule unqualifiedThisOrSuperLiteral() {
		return sequence(
				firstOf(string("this"), string("super")).label("thisOrSuper"),
				group.basics.testLexBreak(),
				group.basics.optWS(),
				testNot(ch('(')),
				SET(actions.createThisOrSuperOrClass(null, TEXT("thisOrSuper"), null)));
	}
	
	/**
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/expressions.html#15.8.2
	 */
	Rule qualifiedClassOrThisOrSuperLiteral() {
		return sequence(
				group.types.type().label("type"),
				ch('.').label("dot"), group.basics.optWS(),
				firstOf(string("this"), string("super"), string("class")).label("thisOrSuperOrClass"),
				group.basics.testLexBreak(),
				group.basics.optWS(),
				SET(actions.createThisOrSuperOrClass(NODE("dot"), TEXT("thisOrSuperOrClass"), VALUE("type"))));
	}
	
	Rule unqualifiedConstructorInvocation() {
		return sequence(
				string("new"), group.basics.testLexBreak(), group.basics.optWS(),
				group.types.typeArguments().label("constructorTypeArgs"),
				group.types.type().label("type"),
				group.structures.methodArguments().label("args"),
				optional(group.structures.typeBody()).label("classBody"),
				SET(actions.createUnqualifiedConstructorInvocation(VALUE("constructorTypeArgs"), VALUE("type"), VALUE("args"), VALUE("classBody"))));
	}
	
	/**
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/arrays.html#10.3
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/expressions.html#15.10
	 */
	Rule arrayCreationExpression() {
		return sequence(
				string("new"), group.basics.testLexBreak(), group.basics.optWS(),
				group.types.nonArrayType().label("type"),
				oneOrMore(sequence(
						ch('[').label("openArray"), group.basics.optWS(),
						optional(anyExpression()).label("dimension"), ch(']'), group.basics.optWS(),
						SET(actions.createDimension(VALUE("dimension"), NODE("openArray"))))),
				optional(arrayInitializer()).label("initializer"),
				SET(actions.createArrayCreationExpression(VALUE("type"), VALUES("oneOrMore/sequence"), VALUE("initializer"))));
	}
	
	public Rule arrayInitializer() {
		return sequence(
				ch('{'), group.basics.optWS(),
				optional(sequence(
						firstOf(arrayInitializer(), anyExpression()).label("head"),
						zeroOrMore(sequence(
								ch(','), group.basics.optWS(),
								firstOf(arrayInitializer(), anyExpression()).label("tail"))),
						optional(ch(',')),
						group.basics.optWS())),
				ch('}'), group.basics.optWS(),
				SET(actions.createArrayInitializerExpression(VALUE("optional/sequence/head"), VALUES("optional/sequence/zeroOrMore/sequence/tail"))));
	}
	
	Rule identifierExpression() {
		return sequence(
				group.basics.identifier(),
				SET(),
				optional(sequence(group.structures.methodArguments(), SET()).label("methodArgs")),
				SET(actions.createPrimary(VALUE(), VALUE("optional/methodArgs"))));
	}
	
	public Rule anyExpression() {
		return assignmentExpressionChaining();
	}
	
	/**
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/statements.html#14.8
	 */
	public Rule statementExpression() {
		return firstOf(
				assignmentExpression(),
				postfixIncrementExpression(),
				prefixIncrementExpression(),
				sequence(dotNewExpressionChaining(), SET(), actions.checkIfMethodOrConstructorInvocation(VALUE())));
	}
	
	public Rule allPrimaryExpressions() {
		return sequence(level1ExpressionChaining(), empty());
	}
	
	/**
	 * P1
	 */
	Rule level1ExpressionChaining() {
		return sequence(
				primaryExpression().label("head"), SET(),
				zeroOrMore(firstOf(
						arrayAccessOperation().label("arrayAccess"),
						methodInvocationWithTypeArgsOperation().label("methodInvocation"),
						select().label("select"))),
				SET(actions.createLevel1Expression(NODE("head"), NODES("zeroOrMore/firstOf"))));
	}
	
	Rule arrayAccessOperation() {
		return sequence(
				ch('['), group.basics.optWS(),
				anyExpression(), SET(), ch(']'), group.basics.optWS(),
				SET(actions.createArrayAccessOperation(VALUE())));
	}
	
	Rule methodInvocationWithTypeArgsOperation() {
		return sequence(
				ch('.').label("dot"), group.basics.optWS(),
				group.types.typeArguments().label("typeArguments"),
				group.basics.identifier().label("name"),
				group.structures.methodArguments().label("methodArguments"),
				SET(actions.createMethodInvocationOperation(NODE("dot"), VALUE("typeArguments"), VALUE("name"), VALUE("methodArguments"))));
	}
	
	Rule select() {
		return sequence(
				group.basics.dotIdentifier().label("identifier"),
				testNot(ch('(')),
				SET(actions.createSelectOperation(VALUE("identifier"))));
	}
	
	/**
	 * P2''
	 * 
	 * This is the relational new operator; not just 'new', but new with context, so: "a.new InnerClass(params)". It is grouped with P2, but for some reason has higher precedence
	 * in all java parsers, and so we give it its own little precedence group here.
	 */
	Rule dotNewExpressionChaining() {
		return sequence(
				level1ExpressionChaining().label("head"), SET(),
				zeroOrMore(sequence(
						sequence(
								ch('.'),
								group.basics.optWS(),
								string("new"),
								group.basics.testLexBreak(),
								group.basics.optWS()),
						group.types.typeArguments().label("constructorTypeArgs"),
						group.basics.identifier().label("innerClassName"),
						group.types.typeArguments().label("classTypeArgs"),
						group.structures.methodArguments().label("methodArguments"),
						optional(group.structures.typeBody()).label("classBody"),
						SET(actions.createQualifiedConstructorInvocation(VALUE("constructorTypeArgs"), NODE("innerClassName"), NODE("classTypeArgs"), VALUE("methodArguments"), VALUE("classBody"))))),
				SET(actions.createChainOfQualifiedConstructorInvocations(NODE("head"), NODES("zeroOrMore/sequence"))));
	}
	
	/**
	 * P2'
	 * Technically, postfix increment operations are in P2 along with all the unary operators like ~ and !, as well as typecasts.
	 * However, because ALL of the P2 expression are right-associative, the postfix operators can be considered as a higher level of precedence.
	 */
	Rule postfixIncrementExpressionChaining() {
		return sequence(
				dotNewExpressionChaining(), SET(),
				zeroOrMore(sequence(
						firstOf(string("++"), string("--")).label("operator"),
						group.basics.optWS()).label("operatorCt")),
				SET(actions.createUnaryPostfixExpression(VALUE(), NODES("zeroOrMore/operatorCt/operator"), TEXTS("zeroOrMore/operatorCt/operator"))));
	}
	
	Rule postfixIncrementExpression() {
		return sequence(
				dotNewExpressionChaining(), SET(),
				oneOrMore(sequence(
						firstOf(string("++"), string("--")).label("operator"),
						group.basics.optWS()).label("operatorCt")),
				SET(actions.createUnaryPostfixExpression(VALUE(), NODES("oneOrMore/operatorCt/operator"), TEXTS("oneOrMore/operatorCt/operator"))));
	}
	
	Rule prefixIncrementExpression() {
		return sequence(
				oneOrMore(sequence(
						firstOf(string("++"), string("--")).label("operator"),
						group.basics.optWS()).label("operatorCt")),
						postfixIncrementExpressionChaining().label("operand"), SET(),
				SET(actions.createUnaryPrefixExpressions(NODE("operand"), NODES("oneOrMore/operatorCt/operator"), TEXTS("oneOrMore/operatorCt/operator"))));
	}
	
	/**
	 * P2
	 */
	Rule level2ExpressionChaining() {
		return firstOf(
				sequence(
						firstOf(
								string("++"), string("--"),
								ch('!'), ch('~'),
								solitarySymbol('+'), solitarySymbol('-'),
								sequence(
										ch('('), group.basics.optWS(),
										group.types.type().label("type"),
										ch(')'),
										testNot(sequence(
												actions.typeIsAlsoLegalAsExpression(UP(UP(VALUE("type")))),
												group.basics.optWS(),
												firstOf(solitarySymbol('+'), solitarySymbol('-'))))).label("cast")
								).label("operator"),
						group.basics.optWS(),
						level2ExpressionChaining().label("operand"), SET(),
						SET(actions.createUnaryPrefixExpression(VALUE("operand"), NODE("operator"), TEXT("operator")))),
					sequence(postfixIncrementExpressionChaining(), SET()));
	}
	
	/**
	 * P3
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.17
	 */
	Rule multiplicativeExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprMultiplicative", firstOf(ch('*'), solitarySymbol('/'), ch('%')), level2ExpressionChaining());
	}
	
	/**
	 * P4
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.18
	 */
	Rule additiveExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprAdditive", firstOf(solitarySymbol('+'), solitarySymbol('-')), multiplicativeExpressionChaining());
	}
	
	/**
	 * P5
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.19
	 */
	Rule shiftExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprShift", firstOf(string(">>>"), string("<<<"), string("<<"), string(">>")), additiveExpressionChaining());
	}
	
	/**
	 * P6
	 * 
	 * Technically 'instanceof' is on equal footing with the other operators, but practically speaking this doesn't hold;
	 * for starters, the RHS of instanceof is a Type and not an expression, and the inevitable type of an instanceof expression (boolean) is
	 * not compatible as LHS to *ANY* of the operators in this class, including instanceof itself. Therefore, pragmatically speaking, there can only
	 * be one instanceof, and it has to appear at the end of the chain.
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.20
	 */
	Rule relationalExpressionChaining() {
		return sequence(
				forLeftAssociativeBinaryExpression("exprRelational", firstOf(string("<="), string(">="), solitarySymbol('<'), solitarySymbol('>')), shiftExpressionChaining()),
				SET(),
				optional(sequence(
						sequence(string("instanceof"), group.basics.testLexBreak(), group.basics.optWS()),
						group.types.type().label("type")).label("typeCt")).label("instanceof"),
				SET(actions.createInstanceOfExpression(VALUE(), VALUE("instanceof/typeCt/type"))));
	}
	
	/**
	 * P7
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.21
	 */
	Rule equalityExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprEquality", firstOf(string("==="), string("!=="), string("=="), string("!=")), relationalExpressionChaining());
	}
	
	/**
	 * P8
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.22
	 */
	Rule bitwiseAndExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprBitwiseAnd", solitarySymbol('&'), equalityExpressionChaining());
	}
	
	/**
	 * P9
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.22
	 */
	Rule bitwiseXorExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprBitwiseXor", solitarySymbol('^'), bitwiseAndExpressionChaining());
	}
	
	/**
	 * P10
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.22
	 */
	Rule bitwiseOrExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprBitwiseOr", solitarySymbol('|'), bitwiseXorExpressionChaining());
	}
	
	/**
	 * P11
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.23
	 */
	Rule conditionalAndExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprLogicalAnd", string("&&"), bitwiseOrExpressionChaining());
	}
	
	/**
	 * P12'
	 * 
	 * This is not a legal operator; however, it is entirely imaginable someone presumes it does exist.
	 * It also has no other sensible meaning, so we will parse it and flag it as a syntax error in AST phase.
	 */
	Rule conditionalXorExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprLogicalXor", string("^^"), conditionalAndExpressionChaining());
	}
	
	/**
	 * P12
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.24
	 */
	Rule conditionalOrExpressionChaining() {
		return forLeftAssociativeBinaryExpression("exprLogicalOr", string("||"), conditionalXorExpressionChaining());
	}
	
	/**
	 * P13
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.25
	 */
	Rule inlineIfExpressionChaining() {
		return sequence(
				conditionalOrExpressionChaining().label("head"),
				SET(),
				optional(
						sequence(
								sequence(ch('?'), testNot(firstOf(ch('.'), ch(':'), ch('?')))).label("operator1"),
								group.basics.optWS(),
								assignmentExpressionChaining().label("tail1"),
								ch(':').label("operator2"),
								group.basics.optWS(),
								inlineIfExpressionChaining().label("tail2")
								)),
				SET(actions.createInlineIfExpression(VALUE("head"),
						NODE("optional/sequence/operator1"), NODE("optional/sequence/operator2"),
						VALUE("optional/sequence/tail1"), VALUE("optional/sequence/tail2"))),
				group.basics.optWS());
	}
	
	/**
	 * P14
	 * 
	 * Not all of the listed operators are actually legal, but if not legal, then they are at least imaginable, so we parse them and flag them as errors in the AST phase.
	 * 
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#15.26
	 */
	Rule assignmentExpressionChaining() {
		return sequence(
				inlineIfExpressionChaining(), SET(),
				optional(sequence(
						assignmentOperator().label("operator"),
						group.basics.optWS(),
						assignmentExpressionChaining().label("RHS"))).label("assignment"),
				SET(actions.createAssignmentExpression(VALUE(), TEXT("assignment/sequence/operator"), VALUE("assignment"))));
	}
	
	// TODO add checks to see if an LHS that isn't valid for assignment shows up as a syntax error of some sort, e.g. a.b() = 2;
	
	Rule assignmentExpression() {
		return sequence(
				assignmentLHS(), SET(),
				assignmentOperator().label("operator"),
				group.basics.optWS(),
				assignmentExpressionChaining().label("RHS"),
				SET(actions.createAssignmentExpression(VALUE(), TEXT("operator"), LAST_VALUE())));
	}
	
	Rule assignmentLHS() {
		return sequence(
				level1ExpressionChaining(), SET(),
				actions.checkIfLevel1ExprIsValidForAssignment(VALUE()));
	}
	
	Rule assignmentOperator() {
		return firstOf(
				solitarySymbol('='),
				string("*="), string("/="), string("+="), string("-="), string("%="),
				string(">>>="), string("<<<="), string("<<="), string(">>="),
				string("&="), string("^="), string("|="),
				string("&&="), string("^^="), string("||="));
	}
	
	/**
	 * @param operator Careful; operator has to match _ONLY_ the operator, not any whitespace around it (otherwise we'd have to remove comments from it, which isn't feasible).
	 */
	@Cached
	Rule forLeftAssociativeBinaryExpression(String labelName, Rule operator, Rule nextHigher) {
		return sequence(
				nextHigher.label("head"), new Action<Node>() {
					@Override public boolean run(Context<Node> context) {
						setContext(context);
						return SET();
					}
				},
				group.basics.optWS(),
				zeroOrMore(sequence(
						operator.label("operator"),
						group.basics.optWS(),
						nextHigher.label("tail"),
						group.basics.optWS())),
				new Action<Node>() {
					@Override public boolean run(Context<Node> context) {
						setContext(context);
						return SET(actions.createLeftAssociativeBinaryExpression(
								NODE("head"),
								NODES("zeroOrMore/sequence/operator"), TEXTS("zeroOrMore/sequence/operator"),
								NODES("zeroOrMore/sequence/tail")));
					}
				},
				group.basics.optWS()).label(labelName);
	}
	
	Rule solitarySymbol(char c) {
		return sequence(ch(c), testNot(ch(c)));
	}
}
