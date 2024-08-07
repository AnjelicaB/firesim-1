temp=$( realpath "$0"  ) && 
cd $( dirname "$temp" )

# Target config
GEM=alveo_u280_firesim_gemmini_rocket_singlecore_no_nic
PFGEM=alveo_u280_firesim_printfgemmini_rocket_singlecore_no_nic
MEDGEM=alveo_u280_firesim_mediumgemmini_rocket_singlecore_no_nic
CTGEM=alveo_u280_firesim_medium_custom_counter_gemmini_rocket_singlecore_no_nic

# Workload specification
CPU=resnet50-cpu-linux.json
OS=resnet50-os-linux.json
WS=resnet50-ws-linux.json
TEST=resnet50-test-linux.json
CPU_F=resnet50-cpu-linux-FG.json
WS_F=resnet50-ws-linux-FG.json

MATMUL=simple_matmul-linux.json
MATMULF=simple_matmul_fence-linux.json
MATMUL_T=simple_matmul-linux-Tr.json
MATMULF_T=simple_matmul_fence-linux-Tr.json

# Trigger specification
IV_start=ffffffff00008013 
IV_end=ffffffff00010013


# Run workloads
# python3 anjelica-runworkload.py <hwdb> <wl_file> <trace_en/disable> 
#       [<output_format>, <trigger_selector>, [<start>, <end>]] [+read_rate=0]
# Trace output formats
    # 0 = human readable; 
    # 1 = binary; 
    # 2 = flamegraph
# Trigger selector
    # 0 = no trigger; 
    # 1 = cycle count trigger; 
    # 2 = program counter trigger; 
    # 3 = instruction trigger
# Autocounter read rate
    # default: 0 = no read; 


##################################################
####################  Matmul  ####################
##################################################


########### lean gemmini ##########
# python3 anjelica-runworkloads.py $GEM $MATMUL no &&
# firesim infrasetup && firesim runworkload 

# &&

# python3 anjelica-runworkloads.py $GEM MATMULF no &&
# firesim infrasetup && firesim runworkload

########### medium gemmini ##########

python3 anjelica-runworkload.py $MEDGEM $MATMUL yes 2 3 $IV_start $IV_end +read_rate=100 &&
firesim infrasetup && firesim runworkload &&


########### default printf lean gemmini ##########

########### custom counter medium gemmini ##########

python3 anjelica-runworkload.py $CTGEM $MATMUL yes 2 3 $IV_start $IV_end +read_rate=100 &&
firesim infrasetup && firesim runworkload