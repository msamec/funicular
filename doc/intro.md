# Introduction to Funicular

TODO: write [great documentation](http://jacobian.org/writing/what-to-write/)

# Funicular document

Funicular document is an  `edn` file that defines all the commands and queries that a particular (backend) system exposes to its API consumers, along with the input and output schemas for every particular command and query.

The documents is basically a tree and it consists of 4 major top level nodes:
* `:api` - where all queries and commands are defined
* `:pipes` - where pipes which acts as command-query glue, are defined (more below)
* `:context` - request context, integrates the document with the host project
* `:logger` - logging



```   
```

