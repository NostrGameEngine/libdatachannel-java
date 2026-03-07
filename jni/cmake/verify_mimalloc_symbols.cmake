if(NOT DEFINED INPUT_FILE OR INPUT_FILE STREQUAL "")
    message(FATAL_ERROR "verify_mimalloc_symbols: INPUT_FILE is required")
endif()

if(NOT EXISTS "${INPUT_FILE}")
    message(FATAL_ERROR "verify_mimalloc_symbols: INPUT_FILE does not exist: ${INPUT_FILE}")
endif()

set(_nm_tool "${NM_TOOL}")
if(_nm_tool STREQUAL "")
    set(_nm_tool "nm")
endif()

set(_bad_symbols "")

if(INPUT_FILE MATCHES "\\.dll$")
    set(_objdump_tool "${OBJDUMP_TOOL}")
    if(_objdump_tool STREQUAL "")
        set(_objdump_tool "objdump")
    endif()

    execute_process(
        COMMAND "${_objdump_tool}" -p "${INPUT_FILE}"
        RESULT_VARIABLE _rc
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err
    )
    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR
            "verify_mimalloc_symbols: failed to inspect PE exports for ${INPUT_FILE} using '${_objdump_tool}'. "
            "objdump stderr: ${_err}")
    endif()

    string(REPLACE "\n" ";" _lines "${_out}")
    set(_in_export_names FALSE)
    foreach(_line IN LISTS _lines)
        if(_line MATCHES "^\\[Ordinal/Name Pointer\\] Table")
            set(_in_export_names TRUE)
            continue()
        endif()
        if(NOT _in_export_names)
            continue()
        endif()
        if(_line MATCHES "^[ \t]*\\[[^]]+\\].*[ \t]([_A-Za-z][_A-Za-z0-9]*)$")
            string(REGEX REPLACE ".*[ \t]([_A-Za-z][_A-Za-z0-9]*)$" "\\1" _sym "${_line}")
            if(_sym MATCHES "^_?mi_" AND NOT _sym MATCHES "^_?libdatachannel_mi_")
                list(APPEND _bad_symbols "${_sym}")
            endif()
        endif()
    endforeach()
else()
    # Try a few nm flag styles (GNU/LLVM, then Darwin).
    set(_nm_flag_sets
        "-g;--defined-only"
        "-gU"
        "-g;-U"
    )

    set(_rc 1)
    set(_out "")
    set(_err "")
    foreach(_flags IN LISTS _nm_flag_sets)
        execute_process(
            COMMAND "${_nm_tool}" ${_flags} "${INPUT_FILE}"
            RESULT_VARIABLE _this_rc
            OUTPUT_VARIABLE _this_out
            ERROR_VARIABLE _this_err
        )
        if(_this_rc EQUAL 0)
            set(_rc 0)
            set(_out "${_this_out}")
            break()
        endif()
        if(NOT _this_err STREQUAL "")
            string(APPEND _err "\n[flags: ${_flags}] ${_this_err}")
        endif()
    endforeach()

    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR
            "verify_mimalloc_symbols: failed to inspect symbols for ${INPUT_FILE} using '${_nm_tool}'. "
            "nm stderr: ${_err}")
    endif()

    string(REPLACE "\n" ";" _lines "${_out}")
    foreach(_line IN LISTS _lines)
        if(_line MATCHES "(^|[ \t])_?mi_[A-Za-z0-9_]+$")
            string(REGEX REPLACE ".*([_]?(mi_[A-Za-z0-9_]+))$" "\\1" _sym "${_line}")
            if(NOT _sym MATCHES "^_?libdatachannel_mi_")
                list(APPEND _bad_symbols "${_sym}")
            endif()
        endif()
    endforeach()
endif()

if(_bad_symbols)
    list(REMOVE_DUPLICATES _bad_symbols)
    string(JOIN ", " _bad_symbols_joined ${_bad_symbols})
    message(FATAL_ERROR
        "verify_mimalloc_symbols: unprefixed mimalloc symbols found in ${INPUT_FILE}: ${_bad_symbols_joined}")
endif()

message(STATUS "verify_mimalloc_symbols: OK for ${INPUT_FILE}")
