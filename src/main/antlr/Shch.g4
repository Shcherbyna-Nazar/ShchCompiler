    grammar Shch;

    program: statement* EOF;

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
        ;

    printStmt: 'print' '(' expr ')' ';';
    printlnStmt: 'println' '(' expr ')' ';';


    varDecl: ('var' | 'val') ID ':' type ('=' expr)? ';';
    assignStmt: ID '=' expr ';';
    readStmt: 'read' '(' ID ')' ';';

    ifStmt: 'if' '(' expr ')' block ('else' block)?;
    whileStmt: 'while' '(' expr ')' block;

    block: '{' statement* '}';

    type: 'Int' | 'Float' | 'String';

    expr
        : expr op=('==' | '!=' | '<' | '>' | '<=' | '>=') expr
        | expr op=('*'|'/') expr
        | expr op=('+'|'-') expr
        | expr op=('&&' | '||') expr
        | '(' expr ')'
        | NUMBER
        | STRING
        | ID
        ;

    STRING: '"' (~["\\] | '\\' .)* '"';
    ID: [a-zA-Z_][a-zA-Z_0-9]*;
    NUMBER: [0-9]+ ('.' [0-9]+)?;
    WS: [ \t\r\n]+ -> skip;
