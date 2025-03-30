grammar Shch;

program: statement* EOF;

statement
    : varDecl
    | assignStmt
    | readStmt
    | printStmt
    | ifStmt
    | whileStmt
    ;

varDecl: ('var' | 'val') ID ':' type ('=' expr)? ';';
assignStmt: ID '=' expr ';';
readStmt: 'read' '(' ID ')' ';';
printStmt: 'print' '(' expr ')' ';';

ifStmt: 'if' '(' expr ')' block ('else' block)?;
whileStmt: 'while' '(' expr ')' block;

block: '{' statement* '}';

type: 'Int' | 'Float';

expr
    : expr op=('==' | '!=' | '<' | '>' | '<=' | '>=') expr
    | expr op=('*'|'/') expr
    | expr op=('+'|'-') expr
    | '(' expr ')'
    | NUMBER
    | ID
    ;

ID: [a-zA-Z_][a-zA-Z_0-9]*;
NUMBER: [0-9]+ ('.' [0-9]+)?;
WS: [ \t\r\n]+ -> skip;
