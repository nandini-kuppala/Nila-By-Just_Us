import os
import numpy as np
import librosa
import pickle
import scipy.signal as signal
from scipy.stats import skew, kurtosis
import streamlit as st
import google.generativeai as genai

# Configure Gemini API
def configure_gemini(api_key):
    genai.configure(api_key=api_key)
    return genai.GenerativeModel("gemini-1.5-flash")

# Load the saved model
def load_model(model_path='baby_cry_model.pkl'):
    with open(model_path, 'rb') as f:
        model_info = pickle.load(f)
    
    print(f"Loaded model with {len(model_info['class_names'])} classes: {model_info['class_names']}")
    return model_info

# Pre-processing functions (these should be identical to the training code)
def pre_emphasis(signal, alpha=0.97):
    return np.append(signal[0], signal[1:] - alpha * signal[:-1])

def bandpass_filter(y, sr=16000, lowcut=142.91, highcut=6620.12, order=5):
    nyquist = 0.5 * sr
    low = lowcut / nyquist
    high = highcut / nyquist
    b, a = signal.butter(order, [low, high], btype='band')
    return signal.filtfilt(b, a, y)

def extract_features(audio_data, sr=16000, n_mfcc=40, n_fft=1024, hop_length=160, win_length=400, window='hann',
                     n_chroma=12, n_mels=13, n_bands=7, fmin=100, bins_per_octave=12, tempogram_win=384, spectral_roll_percent=0.95):
    try:
        # Apply Pre-emphasis
        y = pre_emphasis(audio_data, alpha=0.97)

        # Apply Bandpass Filter
        y = bandpass_filter(y, sr=sr)

        # Zero Crossing Rate & RMS
        zcr = librosa.feature.zero_crossing_rate(y, frame_length=win_length, hop_length=hop_length)
        rms = librosa.feature.rms(y=y, frame_length=win_length, hop_length=hop_length)

        # STFT and Spectral Features
        stft = np.abs(librosa.stft(y, n_fft=n_fft, hop_length=hop_length, win_length=win_length))
        S, phase = librosa.magphase(stft)

        # MFCC and Deltas
        mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=n_mfcc, n_fft=n_fft, hop_length=hop_length,
                                   win_length=win_length, window=window)
        mfcc_delta = librosa.feature.delta(mfcc)
        mfcc_delta2 = librosa.feature.delta(mfcc, order=2)

        # Advanced Spectral Features
        spectral_centroid = librosa.feature.spectral_centroid(S=S, sr=sr)
        spectral_bandwidth = librosa.feature.spectral_bandwidth(S=S, sr=sr)
        spectral_rolloff = librosa.feature.spectral_rolloff(S=S, sr=sr, roll_percent=spectral_roll_percent)
        spectral_contrast = librosa.feature.spectral_contrast(S=S, sr=sr, n_bands=n_bands, fmin=fmin)
        chroma = librosa.feature.chroma_stft(S=S, sr=sr, n_chroma=n_chroma)
        mel_spec = librosa.feature.melspectrogram(y=y, sr=sr, n_fft=n_fft, hop_length=hop_length,
                                                 win_length=win_length, n_mels=n_mels)
        tonnetz = librosa.feature.tonnetz(y=y, sr=sr)

        # Feature aggregation with statistics
        feature_functions = [
            (mfcc, 'mfcc', ['mean', 'std', 'skew', 'max', 'min']),
            (mfcc_delta, 'mfcc_delta', ['mean', 'std']),
            (mfcc_delta2, 'mfcc_delta2', ['mean', 'std']),
            (zcr, 'zcr', ['mean', 'std', 'max']),
            (rms, 'rms', ['mean', 'std', 'max', 'min']),
            (spectral_centroid, 'spectral_centroid', ['mean', 'std', 'max']),
            (spectral_bandwidth, 'spectral_bandwidth', ['mean', 'std', 'max']),
            (spectral_rolloff, 'spectral_rolloff', ['mean', 'std']),
            (spectral_contrast, 'spectral_contrast', ['mean', 'std', 'max']),
            (chroma, 'chroma', ['mean', 'std']),
            (mel_spec, 'mel_spec', ['mean', 'std']),
            (tonnetz, 'tonnetz', ['mean', 'std']),
        ]

        features = []
        feature_names = []

        for feat, feat_name, stats in feature_functions:
            for i in range(feat.shape[0]):
                if 'mean' in stats: 
                    features.append(np.mean(feat[i]))
                    feature_names.append(f"{feat_name}_{i+1}_mean")
                if 'std' in stats: 
                    features.append(np.std(feat[i]))
                    feature_names.append(f"{feat_name}_{i+1}_std")
                if 'skew' in stats: 
                    features.append(skew(feat[i]))
                    feature_names.append(f"{feat_name}_{i+1}_skew")
                if 'kurt' in stats: 
                    features.append(kurtosis(feat[i]))
                    feature_names.append(f"{feat_name}_{i+1}_kurt")
                if 'max' in stats: 
                    features.append(np.max(feat[i]))
                    feature_names.append(f"{feat_name}_{i+1}_max")
                if 'min' in stats: 
                    features.append(np.min(feat[i]))
                    feature_names.append(f"{feat_name}_{i+1}_min")

        # Return features
        return np.array(features)
    except Exception as e:
        print(f"Error processing audio data: {e}")
        return None

# Function to predict the class of a baby cry audio
def predict_baby_cry(audio_path, model_info):
    try:
        # Load the audio file
        y, sr = librosa.load(audio_path, sr=16000)
        
        # Extract features
        features = extract_features(y, sr=sr)
        
        if features is None:
            return "Error: Could not extract features from the audio file."
        
        # Check if feature length matches what the model expects
        expected_length = len(model_info['feature_names'])
        actual_length = len(features)
        
        if actual_length != expected_length:
            print(f"Warning: Feature length mismatch. Model expects {expected_length} features, but got {actual_length}.")
            # Handle length mismatch by padding or truncating if needed
            if actual_length < expected_length:
                # Pad with zeros
                features = np.pad(features, (0, expected_length - actual_length), 'constant')
            else:
                # Truncate
                features = features[:expected_length]
            
        # Reshape for model input
        features = features.reshape(1, -1)
        
        # Make prediction
        model = model_info['model']
        prediction_idx = model.predict(features)[0]
        
        # Map index to class name
        prediction_class = model_info['class_names'][prediction_idx]
        
        # Get prediction probability
        probabilities = model.predict_proba(features)[0]
        confidence = probabilities[prediction_idx] * 100
        
        return {
            'class': prediction_class,
            'confidence': confidence,
            'probabilities': {model_info['class_names'][i]: prob*100 for i, prob in enumerate(probabilities)}
        }
        
    except Exception as e:
        return f"Error during prediction: {str(e)}"

# Function to generate baby soothing recommendations using Gemini AI
def generate_care_tips(cry_type, baby_info=None, chat_history=None):
    try:
        # Combine information for context
        baby_context = ""
        if baby_info:
            baby_context = f"Baby information: Age: {baby_info.get('age', 'Unknown')}, Recent feedings: {baby_info.get('recent_feedings', 'Unknown')}, Sleep pattern: {baby_info.get('sleep_pattern', 'Unknown')}"
        
        # Combine chat history if available
        history_context = ""
        if chat_history:
            history_context = "Previous interactions:\n" + "\n".join(chat_history)
        
        # Create the prompt for Gemini
        prompt = f"""
        You are a pediatric specialist focused on infant care. You need to provide advice to a parent whose baby is crying.

        The baby's cry has been analyzed and classified as: "{cry_type}" with high confidence.
        
        {baby_context}
        
        {history_context}
        
        Please provide:
        1. A brief explanation of what this type of crying typically indicates
        2. 4-5 specific, actionable suggestions the parent can try immediately to soothe the baby
        3. Signs that would indicate the parent should contact a doctor
        
        Format your response in a compassionate, clear way using bullet points where appropriate. 
        Avoid generic advice and focus on tailored recommendations for this specific crying pattern.
        Make sure you dont give too much information only 2 to 3 points with a brief answer if any serious problem then give as detailed one
        """
        
        # Generate response using Gemini
        response = model.generate_content(prompt)
        return response.text if response.text else "Unable to generate recommendations at this time. As a general approach, try holding, rocking, feeding, or checking the diaper of your baby. If crying persists or you're concerned, please consult your pediatrician."
    
    except Exception as e:
        print(f"Error generating recommendations: {e}")
        return "Unable to generate recommendations at this time. As a general approach, try holding, rocking, feeding, or checking the diaper of your baby. If crying persists or you're concerned, please consult your pediatrician."

# Streamlit UI
def main():
    st.title("👶 Baby Cry Analyzer and Helper")
    st.write("Upload a recording of your baby's cry, and I'll analyze it and provide personalized recommendations.")
    
    # Sidebar for configuration
    with st.sidebar:
        st.header("Configuration")
        api_key = st.text_input("Gemini API Key (Required for recommendations)", 
                                type="password", 
                                value=os.getenv("GEMINI_API_KEY", ""))
        
        st.header("Baby Information (Optional)")
        baby_age = st.selectbox("Baby's Age", 
                               ["0-3 months", "3-6 months", "6-9 months", "9-12 months", "Over 12 months"])
        recent_feedings = st.text_area("Recent Feedings (last 24 hours)")
        sleep_pattern = st.text_area("Recent Sleep Pattern")
        
        st.header("Model Settings")
        model_path = st.text_input("Path to model file:", "baby_cry_model.pkl")
    
    # Main area
    uploaded_file = st.file_uploader("Upload baby cry audio (WAV format recommended)", type=["wav", "mp3"])
    
    # Track conversation history
    if 'chat_history' not in st.session_state:
        st.session_state.chat_history = []
    
    if st.button("Analyze Cry"):
        if uploaded_file is not None:
            if not api_key:
                st.warning("Please enter a Gemini API Key to get personalized recommendations.")
                return
            
            # Initialize Gemini model
            global model
            model = configure_gemini(api_key)
            
            # Save the uploaded file temporarily
            temp_file_path = "temp_audio_file.wav"
            with open(temp_file_path, "wb") as f:
                f.write(uploaded_file.getbuffer())
            
            try:
                # Load the model
                with st.spinner("Loading model..."):
                    model_info = load_model(model_path)
                
                # Analyze the audio
                with st.spinner("Analyzing baby cry..."):
                    result = predict_baby_cry(temp_file_path, model_info)
                
                if isinstance(result, dict):
                    # Display prediction results
                    st.success(f"Analysis complete!")
                    st.subheader("Prediction Results")
                    st.write(f"**Type of cry**: {result['class']}")
                    st.write(f"**Confidence**: {result['confidence']:.2f}%")
                    
                    # Show probabilities
                    st.subheader("Probability Breakdown")
                    prob_data = {k: v for k, v in result['probabilities'].items()}
                    for class_name, prob in prob_data.items():
                        st.write(f"- {class_name}: {prob:.2f}%")
                    
                    # Collect baby info for context
                    baby_info = {
                        "age": baby_age,
                        "recent_feedings": recent_feedings,
                        "sleep_pattern": sleep_pattern
                    }
                    
                    # Generate and display recommendations
                    st.subheader("Personalized Recommendations")
                    with st.spinner("Generating personalized recommendations..."):
                        recommendations = generate_care_tips(
                            result['class'], 
                            baby_info=baby_info,
                            chat_history=st.session_state.chat_history
                        )
                    st.markdown(recommendations)
                    
                    # Add to chat history
                    st.session_state.chat_history.append(f"Cry type: {result['class']}")
                    st.session_state.chat_history.append(f"Recommendation provided: {recommendations[:100]}...")
                    
                    # Keep history manageable
                    if len(st.session_state.chat_history) > 10:
                        st.session_state.chat_history = st.session_state.chat_history[-10:]
                    
                else:
                    st.error(f"Error: {result}")
                
                # Clean up the temporary file
                if os.path.exists(temp_file_path):
                    os.remove(temp_file_path)
                    
            except Exception as e:
                st.error(f"Error during analysis: {str(e)}")
                # Clean up the temporary file
                if os.path.exists(temp_file_path):
                    os.remove(temp_file_path)
        else:
            st.warning("Please upload an audio file to analyze.")
    
    # Display chat history
    if st.session_state.chat_history:
        st.subheader("Session History")
        for i, message in enumerate(st.session_state.chat_history):
            st.text(f"{i+1}. {message}")

# Run the app
if __name__ == "__main__":
    main()