"""
Edits config_runtime

Usage: python3 anjelica-runworkload.py <hwdb> <trace_en/disable> <wl_file> [<output_format>, <trigger_selector>, [<start>, <end>]] [<+read_rate=0>]
"""

import ruamel.yaml
yaml = ruamel.yaml.YAML()
import sys
import os
import glob
import string


try:
    hwdb = sys.argv[1]
    wl_file = sys.argv[2]
    trace_en = sys.argv[3]

    read_rate = sys.argv[-1]
    if read_rate.startswith("+read_rate="):
        read_rate = read_rate.split("=")[1]
    else :
        read_rate = 0
except:
    print(" Usage: python3 anjelica-runworkload.py <hwdb> <wl_file> <trace_en/disable> [<output_format>, <trigger_selector>, [<start>, <end>]] [+read_rate=0]\n"+
          " python3 anjelica-runworkload.py alveo_u280_firesim_rocket_singlecore_no_nic yes linux-uniform.json"
          )
    exit()

# check validity of hardware name
with open("config_hwdb.yaml") as f:
    hwdb_check = yaml.load(f)
assert hwdb in hwdb_check, "<hwdb> must be a field in config_hwdb"

# check validity of tracing (is it yes/no)?
assert trace_en == "yes" or trace_en == "no", "<trance_en> must be 'yes' or 'no' "

# check validity of workload name
cwd = os.getcwd()
json_pattern = os.path.join(cwd, 'workloads/*.json')
json_files = glob.glob(json_pattern)
all_json_files = [os.path.basename(json_file) for json_file in json_files]
assert wl_file in all_json_files, "<wl_file> must be a valid workload json file in deploy/workloads"

# updates config_runtime.yaml
with open("config_runtime.yaml") as f:
    y = yaml.load(f)

y['target_config']['default_hw_config'] = hwdb
y['tracing']['enable'] = trace_en
y['workload']['workload_name'] = wl_file
y['autocounter']['read_rate'] = int(read_rate)

# get the output format and trigger select
if trace_en == "yes": 
    try:
        out_format = int(sys.argv[4])
        trigger_sel = int(sys.argv[5])
        assert out_format >= 0 and trigger_sel >= 0 and out_format <= 2 and trigger_sel <= 3, (
            "check <out_format> and <trigger_sel> value range")
    except:
        print("output format and trigger select must be ints")
        exit()
    else:
        y['tracing']['output_format'] = out_format
        y['tracing']['selector'] = trigger_sel

    # get the start and end if needed
    if trigger_sel != 0:
        try: 
            start = sys.argv[6]
            end = sys.argv[7]
            if trigger_sel == 1: # check if it's decimal
                start = int(start)
                end = int(end)
            else: # check if it's hex
                assert (all(c in string.hexdigits for c in start)) and (all(c in string.hexdigits for c in end)), "start and end must be hex"
                if start.isdigit(): start = int(start)
                if end.isdigit(): end = int(end)
        except:
            print("start and end must be ints. If trigger is 1, decimal. If trigger is 2 or 3, hexadecimal")
            exit()
        else: 
            y['tracing']['start'] = start
            y['tracing']['end'] = end

# yaml.dump(y, sys.stdout)

with open("config_runtime.yaml", "w") as ostream:
    yaml.dump(y, ostream)
