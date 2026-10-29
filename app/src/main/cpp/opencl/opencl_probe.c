// JNI binding for io.jeph.pipkin.localmodel.engine.OpenClProbe: the name of the first GPU the vendor's OpenCL driver
// reports, through opencl_loader.c, which loads the driver on this first call.

#define CL_TARGET_OPENCL_VERSION 300

#include <CL/cl.h>
#include <jni.h>

enum { MAX_PLATFORMS = 16 };

JNIEXPORT jstring JNICALL Java_io_jeph_pipkin_localmodel_engine_OpenClProbe_gpuName(JNIEnv * env, jclass clazz) {
  (void) clazz;
  cl_platform_id platforms[MAX_PLATFORMS];
  cl_uint platform_count = 0;
  // Fails without a driver.
  if (clGetPlatformIDs(MAX_PLATFORMS, platforms, &platform_count) != CL_SUCCESS) {
    return NULL;
  }
  if (platform_count > MAX_PLATFORMS) {
    platform_count = MAX_PLATFORMS;
  }
  for (cl_uint i = 0; i < platform_count; i++) {
    cl_device_id gpu;
    cl_uint gpu_count = 0;
    if (clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, 1, &gpu, &gpu_count) != CL_SUCCESS || gpu_count == 0) {
      continue;
    }
    // Device names are short ASCII, such as "QUALCOMM Adreno(TM) 840".
    char name[256];
    if (clGetDeviceInfo(gpu, CL_DEVICE_NAME, sizeof(name), name, NULL) != CL_SUCCESS) {
      return NULL;
    }
    name[sizeof(name) - 1] = '\0';
    return (*env)->NewStringUTF(env, name);
  }
  return NULL;
}
