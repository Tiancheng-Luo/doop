#!/usr/bin/env python
import os
import shutil
import sys

# This script should be executed from the root directory of Doop.

insens_method_weight_dict = {}
sens_method_weight_dict = {}
method_ratio_dict = {}
insens_method_cost_dict = {}
sens_method_cost_dict = {}
sorted_method_ratio_dict = {}

# ----------------- configuration -------------------------
DOOP = './doop'  # './doopOffline'
PRE_ANALYSIS_1 = 'context-insensitive'
PRE_ANALYSIS_2 = '2-object-sensitive+heap'
MAIN_ANALYSIS = 'oracular'
DATABASE = 'last-analysis'
SOUFFLE = 'souffle'

APP = 'temp'
SEP = '"\t"'

ORACULAR_CACHE = 'oracular/cache'
ORACULAR_OUT = 'oracular/out'
TWO_OBJECT_THRESHOLD = 4.0
TWO_TYPE_THRESHOLD = 5.5
ONE_TYPE_THRESHOLD = 6
CI_THRESHOLD = 7
# ---------------------------------------------------------

RESET = '\033[0m'
YELLOW = '\033[33m'
BOLD = '\033[1m'


def run_pre_analyses(init_args):
    if not os.path.exists('oracular'):
        os.mkdir('oracular')
    if not os.path.exists(ORACULAR_CACHE):
        os.mkdir(ORACULAR_CACHE)
    args = [DOOP] + init_args
    args = args + ['-a', PRE_ANALYSIS_1]
    args = args + ["--Xoracular-heuristics"]
    cmd = ' '.join(args)
    print YELLOW + BOLD + 'Running pre-analyses #1 ' + PRE_ANALYSIS_1 + RESET
    # print cmd
    os.system(cmd)
    from_path = os.path.join(DATABASE, 'MethodWeight.csv')
    dump_path = os.path.join(ORACULAR_CACHE, '%s' % "InsensitiveSum.facts")
    shutil.copyfile(from_path, dump_path)

    print YELLOW + BOLD + 'Running pre-analyses #2 ' + PRE_ANALYSIS_2 + RESET
    args = [DOOP] + init_args
    args = args + ['-a', PRE_ANALYSIS_2]
    args = args + ["--Xoracular-heuristics"]
    cmd = ' '.join(args)
    os.system(cmd)
    from_path = os.path.join(DATABASE, 'MethodWeight.csv')
    dump_path = os.path.join(ORACULAR_CACHE, '%s' % "SensitiveSum.facts")
    shutil.copyfile(from_path, dump_path)


def run_oracular_analysis_classification():
    print YELLOW + BOLD + 'Running Oracular classification ' + RESET
    insens_file = open(ORACULAR_CACHE + "/InsensitiveSum.facts", 'r')
    sens_file = open(ORACULAR_CACHE + "/SensitiveSum.facts", 'r')

    for line in insens_file:
        pieces = line.split('\t')
        method = pieces[0]
        weight = int(pieces[1])
        insens_method_weight_dict.update({method: weight})

    print YELLOW + BOLD + 'INSENS methods ' + str(len(insens_method_weight_dict.keys())) + RESET
    insens_file.close()

    for line in sens_file:
        pieces = line.split('\t')
        method = pieces[0]
        weight = int(pieces[1])
        sens_method_weight_dict.update({method: weight})

    print YELLOW + BOLD + 'SENS methods ' + str(len(sens_method_weight_dict.keys())) + RESET
    sens_file.close()

    missing_methods = 0
    special_cs_file = open(ORACULAR_CACHE + "/SpecialCSMethods.csv", "w")

    for method in insens_method_weight_dict.keys():
        if insens_method_weight_dict.get(method) == 0:
            method_ratio_dict.update({method: 0.0})
        else:
            if sens_method_weight_dict.__contains__(method):
                insens_weight = insens_method_weight_dict.get(method)
                sens_weight = sens_method_weight_dict.get(method)
                ratio = float(sens_weight) / float(insens_weight)
                method_ratio_dict.update({method: ratio})
            else:
                special_cs_file.write(method + "\t" "2-object\n")
                missing_methods += 1

    sorted_method_ratio_dict = sorted(method_ratio_dict.items(), key=lambda x: x[1])

    optimal_ratio_threshold = binary_search_threshold(method_ratio_dict.values())

    two_object_sensitive_methods = 0
    two_type_sensitive_methods = 0
    one_type_sensitive_methods = 0
    context_insensitive_methods = 0
    for method, ratio in sorted_method_ratio_dict:

        if ratio <= optimal_ratio_threshold:
            special_cs_file.write(method + "\t" "2-object\n")
            two_object_sensitive_methods += 1
        # elif method_ratio_average <= TWO_TYPE_THRESHOLD:
        #     special_cs_file.write(method + "\t" + "2-type\n")
        #     two_type_sensitive_methods += 1
        # elif method_ratio_average <= ONE_TYPE_THRESHOLD:
        #     special_cs_file.write(method + "\t" + "1-type\n")
        #     one_type_sensitive_methods += 1
        else:
            special_cs_file.write(method + "\t" + "context-insensitive\n")
            context_insensitive_methods += 1

    special_cs_file.close()

    print YELLOW + BOLD + "2-object methods: " + str(two_object_sensitive_methods) + RESET
    print YELLOW + BOLD + "2-type methods: " + str(two_type_sensitive_methods) + RESET
    print YELLOW + BOLD + "1-type methods: " + str(one_type_sensitive_methods) + RESET
    print YELLOW + BOLD + "context-insensitive methods: " + str(context_insensitive_methods) + RESET


def calculate_analysis_threshold(ratio_threshold):
    analysis_weight = 0
    for method, ratio in sorted_method_ratio_dict:
        if ratio <= ratio_threshold:
            analysis_weight += sens_method_cost_dict.get(method)
        else:
            analysis_weight += insens_method_cost_dict.get(method)
    return analysis_weight


def binary_search_threshold(threshold_list):
    if 2 < calculate_analysis_threshold(threshold_list[0]):
        return threshold_list[0]
    if 2 > calculate_analysis_threshold(threshold_list[len(threshold_list) - 1]):
        return threshold_list[len(threshold_list)-1]

    low = 0
    high = len(threshold_list) - 1

    while low >= high:
        mid = low + (high - low) / 2

        if 2 < calculate_analysis_threshold(threshold_list[mid]):
            high = mid - 1
        elif 2 > calculate_analysis_threshold(threshold_list[mid]):
            low = mid + 1
        else:
            return threshold_list[mid]

    if (calculate_analysis_threshold(threshold_list[low]) - 2) < (2 - calculate_analysis_threshold(threshold_list[high])):
        return threshold_list[low]
    else:
        return threshold_list[high]


def run_main_analysis(args, oracular_file):
    args = [DOOP] + args
    args = args + ['-a', MAIN_ANALYSIS]
    args = args + ['--special-cs-methods', oracular_file]
    cmd = ' '.join(args)
    print YELLOW + BOLD + 'Running main (Oracular-guided) analysis ...' + RESET
    # print cmd
    os.system(cmd)


def run(args):
    # runPreAnalyses(args)
    run_oracular_analysis_classification()
    run_main_analysis(args, ORACULAR_CACHE + "/SpecialCSMethods.csv")


if __name__ == '__main__':
    run(sys.argv[1:])
