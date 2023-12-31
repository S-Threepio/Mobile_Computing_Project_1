package com.swanand.mc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.swanand.mc.database.CovaDB
import com.swanand.mc.databinding.DefaultLayoutBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.lang.Math.sqrt
import kotlin.math.abs


class LauncherFragment : Fragment(), HeartRateCallback {
    private lateinit var binding: DefaultLayoutBinding // Use your generated binding class
    private lateinit var database: CovaDB
    private lateinit var symptomRatingMap: Map<String, Float>
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometerSensor: Sensor
    private val accelerometerDataList = mutableListOf<FloatArray>()
    private var accelerometerResult = 0
    private var heartRateResult = 0
    private lateinit var videoCaptureLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val VIDEO_CAPTURE_REQUEST_CODE = 101
    }
    private lateinit var contentUri:Uri


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Initialize View Binding for the fragment
        binding = DefaultLayoutBinding.inflate(inflater, container, false)
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Handle the back button press in the child fragment
                val currentFragment =
                    childFragmentManager.findFragmentById(binding.childContainer.id)


                if (currentFragment == null) {
                    requireActivity().finish()
                }


                if (currentFragment is SymptomsFragment) {
                    childFragmentManager.popBackStack()
                    onCloseSymptomsFragment()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        return binding.root
    }

    private fun openVideoCamera() {
        startLoader()
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        //intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 45)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, contentUri)
        try {
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                videoCaptureLauncher.launch(intent)
            } else {
                // Handle the case where no camera app is available
                Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Handle any exceptions here
            e.printStackTrace()
            Toast.makeText(requireContext(), "Camera app crashed", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        database = CovaDB.getInstance(this.requireContext())
        val videoF = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "finger_video.mp4")

        contentUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", videoF)

        videoCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {

                // Video recording was successful, you can get the URI of the recorded video
                val data: Intent? = result.data
                val videoUri: Uri? = data?.data
                if (videoUri != null) {
                    //heartRateCalculator = HeartRateCalculator(videoUri)
                    Toast.makeText(requireContext(), "URI is $videoUri", Toast.LENGTH_LONG).show()

                    HeartRateDetector(this).execute(videoUri)
                }
            }
        }



        binding.breathingRateText.text = accelerometerResult.toString()

        binding.uploadSymptoms.setOnClickListener {
            val fragContainer = binding.childContainer
            fragContainer.visible()
            binding.contentLayout.gone()

            childFragmentManager.beginTransaction()
                .replace(
                    binding.childContainer.id,
                    SymptomsFragment(heartRateResult, accelerometerResult)
                )
                .addToBackStack(null)
                .commit()
        }

        binding.clearDB.setOnClickListener {
            clearDatabase()
        }

        binding.breathingRate.setOnClickListener {
               // Start the loader (com.swanand.mc.show loading indicator)
                startLoader()

                // Reset the accelerometer data list
                accelerometerDataList.clear()

                // Start reading accelerometer data
                startAccelerometerDataCollection()
            }


        binding.heartRate.setOnClickListener {

            //Setting up permissions for recording video for heart rate
            permissionGiver(requireActivity())

            if (hasCameraPermission() && hasStoragePermission()) {
                // You have the necessary permissions, proceed with opening the camera
                openVideoCamera()
            } else {
                // Request permissions
                requestPermissions()
            }

        }
    }

    private fun startAccelerometerDataCollection() {
        sensorManager.registerListener(
            accelerometerListener,
            accelerometerSensor,
            1000000,
            1000000
        )
    }

    private val accelerometerListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Handle accuracy changes if needed
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                // Collect accelerometer data (e.g., event.values[0], event.values[1], event.values[2])
                    val newValues = FloatArray(3)
                    System.arraycopy(event.values, 0, newValues, 0, 3)
                    accelerometerDataList.add(newValues)

                // Check if you have collected 450 values, then stop reading
                if (accelerometerDataList.size >= 450) {
                    stopAccelerometerDataCollection()
                    stopLoader()
                    // Process the collected data as needed
                }
            }
        }
    }

    private fun stopAccelerometerDataCollection() {
        sensorManager.unregisterListener(accelerometerListener, accelerometerSensor)

        accelerometerResult = calculateAccelerometerData()
        binding.breathingRateText.text = accelerometerResult.toString()
    }

    private fun stopLoader() {
        binding.loader.gone()
        binding.contentLayout.visible()
    }

    private fun startLoader() {
        binding.loader.visible()
        binding.contentLayout.gone()
    }


    private fun clearDatabase() {
        GlobalScope.launch(Dispatchers.IO) {
            // Insert the dummy data into the Room database using your SymptomsDBDao
            database.symptomsDBDao().deleteAll()
        }
    }

    fun calculateAccelerometerData():Int{
            var previousValue = 0f
            var currentValue = 0f
            previousValue = 10f
            var k=0
        for (i in 10..449) {
            var (x, y, z) = accelerometerDataList[i]
            currentValue = sqrt(
                Math.pow(z.toDouble(), 2.0) + Math.pow(
                    x.toDouble(),
                    2.0
                ) + Math.pow(y.toDouble(), 2.0)
            ).toFloat()
            if (abs(x = previousValue - currentValue) > 0.15) {
                k++
            }
                previousValue=currentValue
            }
            val ret= (k/45.00)
        return (ret*30).toInt()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            VIDEO_CAPTURE_REQUEST_CODE
        )
    }

    fun onCloseSymptomsFragment() {
        binding.childContainer.gone()
        binding.contentLayout.visible()
    }

    override fun onHeartRateCalculated(heartRate: Int) {
        stopLoader()
        binding.heartRateText.text = heartRate.toString()
        Toast.makeText(requireContext(), "Heart Rate: $heartRate", Toast.LENGTH_SHORT).show()
        heartRateResult = heartRate
    }


}
