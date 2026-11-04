#!/usr/bin/env python3
# Stands in for util-linux's `script -qfec <command> /dev/null`, which the terminal mode runs and which macOS lacks:
# runs <command> with $SHELL on a new pseudo-terminal, copying the host's pipes to and from it, and exits as it did.
import os
import pty
import sys

command = sys.argv[sys.argv.index("-qfec") + 1]
status = pty.spawn([os.environ.get("SHELL", "/bin/sh"), "-c", command])
sys.exit(os.waitstatus_to_exitcode(status))
