ELite
=====

* is a **dynamic** and **functional** language for the Java Virtual Machine
* supports **Domain-Specific Languages** to make your code easy to read and maintain
* supports **Object Oriented Paradigm**
* seamlessly integrates with all existing Java classes and libraries

## Installation

    $ git clone https://github.com/hongun/ELite.git
    $ cd ELite
    $ ant

## Running

Navigate to the build release directory and run the elite.sh shell script.

    $ cd release/elite-5.0
    $ bin/elite.sh

ELite will display the welcome message:

    Welcome to ELite (Version 0.5.0)
    Copyright (c) 2006-2011 Daniel Yuan.
    ELite comes with ABSOLUTELY NO WARRANTY. This is free software,
    and you are welcome to redistribute it under certain conditions.
    
    > 

Type expressions and statements at the prompt. Type `quit` to exit the interactive shell. You can exam and run the sample scripts located in the sample subdirectory. For example:

    $ bin/elite.sh sample/hello.xel
