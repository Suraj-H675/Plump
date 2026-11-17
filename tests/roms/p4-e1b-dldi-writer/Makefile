# SPDX-License-Identifier: CC0-1.0
#
# Build with the official BlocksDS / Wonderful Toolchain environment:
#   eval "$(wf-config env generate -a)"
#   make ROM_ID=0xA1A10001 NAME=plump_e1b_alpha
#   make dldipatch ROM_ID=0xA1A10001 NAME=plump_e1b_alpha
#   make ROM_ID=0xB2B20002 NAME=plump_e1b_beta
#   make dldipatch ROM_ID=0xB2B20002 NAME=plump_e1b_beta

BLOCKSDS ?= /opt/wonderful/thirdparty/blocksds/core

NAME ?= plump_e1b_alpha
GAME_TITLE ?= PLUMP E1B
GAME_SUBTITLE ?= DLDI WRITER
GAME_AUTHOR ?= PLUMP

ROM_ID ?= 0xA1A10001
DEFINES += -DPLUMP_ROM_ID=$(ROM_ID)

include $(BLOCKSDS)/sys/default_makefiles/rom_arm9/Makefile
