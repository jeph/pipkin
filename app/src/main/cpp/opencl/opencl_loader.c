// The OpenCL functions that ggml's OpenCL backend and pipkin-opencl-probe call, each forwarding to the vendor's driver,
// libOpenCL.so, which loads on the first call. Linking the driver instead would make Android refuse to load either
// library on a phone without one, such as an emulator; without it, OpenCL here finds no platform and every other
// function fails. Add a function to CL_FUNCTIONS and define it below when a new llama.cpp calls one more: the link
// fails on any that's missing.

#define CL_TARGET_OPENCL_VERSION 300
// ggml-opencl creates its command queue with OpenCL 1.2's clCreateCommandQueue.
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS

#include <CL/cl.h>
#include <CL/cl_ext.h>
#include <CL/cl_function_types.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#define CL_FUNCTIONS(X)           \
  X(clGetPlatformIDs)             \
  X(clGetPlatformInfo)            \
  X(clGetDeviceIDs)               \
  X(clGetDeviceInfo)              \
  X(clCreateContext)              \
  X(clCreateCommandQueue)         \
  X(clCreateBuffer)               \
  X(clCreateBufferWithProperties) \
  X(clCreateSubBuffer)            \
  X(clCreateImage)                \
  X(clReleaseMemObject)           \
  X(clCreateProgramWithSource)    \
  X(clCreateProgramWithBinary)    \
  X(clBuildProgram)               \
  X(clGetProgramInfo)             \
  X(clGetProgramBuildInfo)        \
  X(clReleaseProgram)             \
  X(clCreateKernel)               \
  X(clSetKernelArg)               \
  X(clGetKernelWorkGroupInfo)     \
  X(clGetKernelSubGroupInfo)      \
  X(clReleaseKernel)              \
  X(clWaitForEvents)              \
  X(clReleaseEvent)               \
  X(clFlush)                      \
  X(clFinish)                     \
  X(clEnqueueReadBuffer)          \
  X(clEnqueueWriteBuffer)         \
  X(clEnqueueCopyBuffer)          \
  X(clEnqueueFillBuffer)          \
  X(clEnqueueMapBuffer)           \
  X(clEnqueueUnmapMemObject)      \
  X(clEnqueueNDRangeKernel)       \
  X(clEnqueueMarkerWithWaitList)  \
  X(clEnqueueBarrierWithWaitList)

// The driver's functions, null for those it lacks or all of them without a driver.
static struct {
#define FIELD(name) name##_fn name;
  CL_FUNCTIONS(FIELD)
#undef FIELD
} driver;

static pthread_once_t driver_once = PTHREAD_ONCE_INIT;

static void load_driver(void) {
  // A model on the CPU never touches the driver, so a driver that crashes as it sets up can't take the CPU down too.
  const char * wanted = getenv("PIPKIN_OPENCL");
  if (wanted != NULL && strcmp(wanted, "off") == 0) {
    __android_log_print(ANDROID_LOG_INFO, "PipkinOpenCL", "OpenCL is off for this process");
    return;
  }
  // Android 12 and later let an app load it only when the manifest lists it in <uses-native-library>.
  void * library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
  if (library == NULL) {
    __android_log_print(ANDROID_LOG_INFO, "PipkinOpenCL", "No OpenCL driver: %s", dlerror());
    return;
  }
#define RESOLVE(name) driver.name = (name##_fn) dlsym(library, #name);
  CL_FUNCTIONS(RESOLVE)
#undef RESOLVE
}

static void ensure_driver(void) {
  pthread_once(&driver_once, load_driver);
}

// A function returning an error code: the driver's, or CL_INVALID_OPERATION when the driver lacks it.
#define FORWARD(name, params, args)                               \
  cl_int CL_API_CALL name params {                                \
    ensure_driver();                                              \
    return driver.name ? driver.name args : CL_INVALID_OPERATION; \
  }

// A function returning an object or pointer, and its error code through errcode_ret.
#define FORWARD_CREATE(type, name, params, args)  \
  type CL_API_CALL name params {                  \
    ensure_driver();                              \
    if (driver.name) {                            \
      return driver.name args;                    \
    }                                             \
    if (errcode_ret) {                            \
      *errcode_ret = CL_INVALID_OPERATION;        \
    }                                             \
    return NULL;                                  \
  }

// Without a driver there are no platforms, which OpenCL's own ICD loader reports this way, and no platform means no
// object to call any other function on.
cl_int CL_API_CALL clGetPlatformIDs(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms) {
  ensure_driver();
  if (driver.clGetPlatformIDs) {
    return driver.clGetPlatformIDs(num_entries, platforms, num_platforms);
  }
  if (num_platforms) {
    *num_platforms = 0;
  }
  return CL_PLATFORM_NOT_FOUND_KHR;
}

FORWARD(clGetPlatformInfo,
        (cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value,
         size_t * param_value_size_ret),
        (platform, param_name, param_value_size, param_value, param_value_size_ret))

FORWARD(clGetDeviceIDs,
        (cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices,
         cl_uint * num_devices),
        (platform, device_type, num_entries, devices, num_devices))

FORWARD(clGetDeviceInfo,
        (cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value,
         size_t * param_value_size_ret),
        (device, param_name, param_value_size, param_value, param_value_size_ret))

FORWARD_CREATE(cl_context, clCreateContext,
               (const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices,
                void(CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb,
                                               void * user_data),
                void * user_data, cl_int * errcode_ret),
               (properties, num_devices, devices, pfn_notify, user_data, errcode_ret))

FORWARD_CREATE(cl_command_queue, clCreateCommandQueue,
               (cl_context context, cl_device_id device, cl_command_queue_properties properties,
                cl_int * errcode_ret),
               (context, device, properties, errcode_ret))

FORWARD_CREATE(cl_mem, clCreateBuffer,
               (cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret),
               (context, flags, size, host_ptr, errcode_ret))

FORWARD_CREATE(cl_mem, clCreateBufferWithProperties,
               (cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size,
                void * host_ptr, cl_int * errcode_ret),
               (context, properties, flags, size, host_ptr, errcode_ret))

FORWARD_CREATE(cl_mem, clCreateSubBuffer,
               (cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type,
                const void * buffer_create_info, cl_int * errcode_ret),
               (buffer, flags, buffer_create_type, buffer_create_info, errcode_ret))

FORWARD_CREATE(cl_mem, clCreateImage,
               (cl_context context, cl_mem_flags flags, const cl_image_format * image_format,
                const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret),
               (context, flags, image_format, image_desc, host_ptr, errcode_ret))

FORWARD(clReleaseMemObject, (cl_mem memobj), (memobj))

FORWARD_CREATE(cl_program, clCreateProgramWithSource,
               (cl_context context, cl_uint count, const char ** strings, const size_t * lengths,
                cl_int * errcode_ret),
               (context, count, strings, lengths, errcode_ret))

FORWARD_CREATE(cl_program, clCreateProgramWithBinary,
               (cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths,
                const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret),
               (context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret))

FORWARD(clBuildProgram,
        (cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options,
         void(CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data),
        (program, num_devices, device_list, options, pfn_notify, user_data))

FORWARD(clGetProgramInfo,
        (cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value,
         size_t * param_value_size_ret),
        (program, param_name, param_value_size, param_value, param_value_size_ret))

FORWARD(clGetProgramBuildInfo,
        (cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size,
         void * param_value, size_t * param_value_size_ret),
        (program, device, param_name, param_value_size, param_value, param_value_size_ret))

FORWARD(clReleaseProgram, (cl_program program), (program))

FORWARD_CREATE(cl_kernel, clCreateKernel,
               (cl_program program, const char * kernel_name, cl_int * errcode_ret),
               (program, kernel_name, errcode_ret))

FORWARD(clSetKernelArg,
        (cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value),
        (kernel, arg_index, arg_size, arg_value))

FORWARD(clGetKernelWorkGroupInfo,
        (cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size,
         void * param_value, size_t * param_value_size_ret),
        (kernel, device, param_name, param_value_size, param_value, param_value_size_ret))

FORWARD(clGetKernelSubGroupInfo,
        (cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size,
         const void * input_value, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
        (kernel, device, param_name, input_value_size, input_value, param_value_size, param_value,
         param_value_size_ret))

FORWARD(clReleaseKernel, (cl_kernel kernel), (kernel))

FORWARD(clWaitForEvents, (cl_uint num_events, const cl_event * event_list), (num_events, event_list))

FORWARD(clReleaseEvent, (cl_event event), (event))

FORWARD(clFlush, (cl_command_queue command_queue), (command_queue))

FORWARD(clFinish, (cl_command_queue command_queue), (command_queue))

FORWARD(clEnqueueReadBuffer,
        (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size,
         void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
        (command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list,
         event))

FORWARD(clEnqueueWriteBuffer,
        (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size,
         const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
        (command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list,
         event))

FORWARD(clEnqueueCopyBuffer,
        (cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset,
         size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list,
         cl_event * event),
        (command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list,
         event_wait_list, event))

FORWARD(clEnqueueFillBuffer,
        (cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset,
         size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
        (command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list,
         event))

FORWARD_CREATE(void *, clEnqueueMapBuffer,
               (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags,
                size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list,
                cl_event * event, cl_int * errcode_ret),
               (command_queue, buffer, blocking_map, map_flags, offset, size, num_events_in_wait_list,
                event_wait_list, event, errcode_ret))

FORWARD(clEnqueueUnmapMemObject,
        (cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list,
         const cl_event * event_wait_list, cl_event * event),
        (command_queue, memobj, mapped_ptr, num_events_in_wait_list, event_wait_list, event))

FORWARD(clEnqueueNDRangeKernel,
        (cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset,
         const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list,
         const cl_event * event_wait_list, cl_event * event),
        (command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size,
         num_events_in_wait_list, event_wait_list, event))

FORWARD(clEnqueueMarkerWithWaitList,
        (cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list,
         cl_event * event),
        (command_queue, num_events_in_wait_list, event_wait_list, event))

FORWARD(clEnqueueBarrierWithWaitList,
        (cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list,
         cl_event * event),
        (command_queue, num_events_in_wait_list, event_wait_list, event))
