    grammar Shch;

   program: (structDecl | functionDecl | statement)* EOF;

   structDecl: 'struct' ID '{' structField* '}';
   structField: ID ':' type ';';

   functionDecl: 'fun' ID '(' parameters? ')' ':' type block;
   parameters: parameter (',' parameter)*;
   parameter: ID ':' type;

    COMMENT
      : '//' ~[\r\n]* -> skip
      ;

    COMMENT_ML
      : '/*' .*? '*/' -> skip
      ;

    statement
        : varDecl
        | assignStmt
        | readStmt
        | printStmt
        | printlnStmt
        | ifStmt
        | whileStmt
        | returnStmt
        | exprStmt
        | block
        ;

    exprStmt: expr ';';     // ✅ i tu nową regułę

    returnStmt: 'return' expr? ';';

    printStmt: 'print' '(' expr ')' ';';
    printlnStmt: 'println' '(' expr ')' ';';


    varDecl: ('var' | 'val') ID ':' type ('=' expr)? ';';
    assignStmt: ID '=' expr ';';
    readStmt: 'read' '(' ID ')' ';';

    ifStmt: 'if' '(' expr ')' block ('else' block)?;
    whileStmt: 'while' '(' expr ')' block;

    block: '{' statement* '}';

    type: ID;

expr
    : expr '.' ID
    | ID '(' (expr (',' expr)*)? ')'    // function or constructor call
    | ID '(' (expr (',' expr)*)? ')'    // <-- funkcja z argumentami (0+)
    | ID assign='=' expr
    | not='!' expr
    | sign='-' expr
    | expr op='&' expr
    | expr op='^' expr
    | expr op='|' expr
    | expr op=('&&' | '||') expr
    | expr op=('==' | '!=' | '<' | '>' | '<=' | '>=') expr
    | expr op=('*'|'/'|'%') expr
    | expr op=('+'|'-') expr
    | '(' expr ')'
    | NUMBER
    | STRING
    | ID
    | 'true'
    | 'false'
    ;



    TRUE: 'true';
    FALSE: 'false';
    ID: [a-zA-Z_][a-zA-Z_0-9]*;
    NUMBER: [0-9]+ ('.' [0-9]+)?;
    STRING: '"' (~["\\] | '\\' .)* '"';
    WS: [ \t\r\n]+ -> skip;
