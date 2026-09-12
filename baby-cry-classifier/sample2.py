import os
import numpy as np
import librosa
import pickle
import scipy.signal as signal
from scipy.stats import skew, kurtosis
import streamlit as st
import google.generativeai as genai

# Configure page settings
st.set_page_config(
    page_title="Baby Cry Analyzer",
    page_icon="👶",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Custom CSS for calm, light colors
st.markdown("""
<style>
    .main {
        background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
        padding: 2rem;
    }
    
    .stApp {
        background: linear-gradient(135deg, #e8f4f8 0%, #f0f8ff 100%);
    }
    
    .title-container {
        background: linear-gradient(135deg, #a8e6cf 0%, #88d8c0 100%);
        padding: 2rem;
        border-radius: 20px;
        margin-bottom: 2rem;
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
        text-align: center;
        border: 2px solid rgba(255, 255, 255, 0.3);
    }
    
    .title-container h1 {
        color: #2c3e50;
        font-size: 3rem;
        margin-bottom: 0.5rem;
        text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
    }
    
    .title-container p {
        color: #34495e;
        font-size: 1.2rem;
        margin-bottom: 0;
    }
    
    .info-card {
        background: rgba(255, 255, 255, 0.9);
        padding: 1.5rem;
        border-radius: 15px;
        margin: 1rem 0;
        box-shadow: 0 2px 15px rgba(0, 0, 0, 0.08);
        border-left: 4px solid #7fb3d3;
    }
    
    .success-card {
        background: linear-gradient(135deg, #d4edda 0%, #c3e6cb 100%);
        padding: 1.5rem;
        border-radius: 15px;
        margin: 1rem 0;
        box-shadow: 0 2px 15px rgba(0, 0, 0, 0.08);
        border-left: 4px solid #28a745;
    }
    
    .recommendation-card {
        background: linear-gradient(135deg, #fff3cd 0%, #ffeaa7 100%);
        padding: 1.5rem;
        border-radius: 15px;
        margin: 1rem 0;
        box-shadow: 0 2px 15px rgba(0, 0, 0, 0.08);
        border-left: 4px solid #ffc107;
    }
    
    .sidebar-section {
        background: rgba(255, 255, 255, 0.8);
        padding: 1rem;
        border-radius: 10px;
        margin-bottom: 1rem;
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
    }
    
    .stButton > button {
        background: linear-gradient(135deg, #74b9ff 0%, #0984e3 100%);
        color: white;
        border: none;
        padding: 0.75rem 2rem;
        border-radius: 25px;
        font-size: 1.1rem;
        font-weight: 600;
        box-shadow: 0 4px 15px rgba(0, 0, 0, 0.2);
        transition: all 0.3s ease;
        width: 100%;
        margin-top: 1rem;
    }
    
    .stButton > button:hover {
        background: linear-gradient(135deg, #0984e3 0%, #74b9ff 100%);
        transform: translateY(-2px);
        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.3);
    }
    
    .upload-section {
        background: linear-gradient(135deg, #e8f4f8 0%, #d1ecf1 100%);
        padding: 2rem;
        border-radius: 20px;
        margin: 2rem 0;
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
        border: 2px dashed #74b9ff;
        text-align: center;
    }
    
    .probability-item {
        background: rgba(255, 255, 255, 0.7);
        padding: 0.5rem 1rem;
        margin: 0.3rem 0;
        border-radius: 10px;
        border-left: 3px solid #74b9ff;
        box-shadow: 0 1px 5px rgba(0, 0, 0, 0.05);
    }
    
    .history-item {
        background: rgba(255, 255, 255, 0.6);
        padding: 0.8rem;
        margin: 0.3rem 0;
        border-radius: 8px;
        border-left: 2px solid #a8e6cf;
        font-size: 0.9rem;
        color: #2c3e50;
    }
    
    .section-header {
        color: #2c3e50;
        font-size: 1.5rem;
        margin-bottom: 1rem;
        padding-bottom: 0.5rem;
        border-bottom: 2px solid #a8e6cf;
    }
    
    .stSelectbox > div > div {
        background: rgba(255, 255, 255, 0.9);
        border-radius: 10px;
    }
    
    .stTextArea > div > div {
        background: rgba(255, 255, 255, 0.9);
        border-radius: 10px;
    }
    
    .stFileUploader > div {
        background: rgba(255, 255, 255, 0.9);
        border-radius: 15px;
        padding: 1rem;
    }
    
    .metric-container {
        background: linear-gradient(135deg, #dcedc8 0%, #aed581 100%);
        padding: 1rem;
        border-radius: 15px;
        margin: 0.5rem 0;
        text-align: center;
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
    }
    
    .metric-value {
        font-size: 1.5rem;
        font-weight: bold;
        color: #2e7d32;
        margin-bottom: 0.2rem;
    }
    
    .metric-label {
        font-size: 0.9rem;
        color: #388e3c;
        text-transform: uppercase;
        letter-spacing: 0.5px;
    }
</style>
""", unsafe_allow_html=True)

# Supplied via the environment. Never commit a key.
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")

# Configure Gemini API
def configure_gemini(api_key):
    genai.configure(api_key=api_key)
    return genai.GenerativeModel("gemini-1.5-flash")

# Load the saved model
def load_model(model_path='baby_cry_model.pkl'):
    with open(r"baby_cry_model2.pkl", 'rb') as f:
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
        # Initialize Gemini model
        model = configure_gemini(GEMINI_API_KEY)
        
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
    # Title section
    st.markdown("""
    <div class="title-container">
        <h1>👶 Baby Cry Analyzer & Helper</h1>
        <p>Advanced AI-powered analysis to understand your baby's needs and provide personalized care recommendations</p>
    </div>
    """, unsafe_allow_html=True)
    
    # Sidebar for configuration
    with st.sidebar:
        st.markdown('<div class="sidebar-section">', unsafe_allow_html=True)
        st.markdown('<h3 class="section-header">👶 Baby Information</h3>', unsafe_allow_html=True)
        
        baby_age = st.selectbox(
            "Baby's Age", 
            ["0-3 months", "3-6 months", "6-9 months", "9-12 months", "Over 12 months"],
            help="Select your baby's current age range"
        )
        
        recent_feedings = st.text_area(
            "Recent Feedings", 
            placeholder="e.g., Last fed 2 hours ago, breastfed for 15 minutes...",
            help="Describe recent feeding patterns in the last 24 hours"
        )
        
        sleep_pattern = st.text_area(
            "Sleep Pattern", 
            placeholder="e.g., Slept 3 hours, woke up crying...",
            help="Describe recent sleep patterns and any changes"
        )
        st.markdown('</div>', unsafe_allow_html=True)
        
        st.markdown('<div class="sidebar-section">', unsafe_allow_html=True)
        st.markdown('<h3 class="section-header">⚙️ Model Settings</h3>', unsafe_allow_html=True)
        model_path = st.text_input(
            "Model File Path:", 
            "baby_cry_model.pkl",
            help="Path to your trained baby cry classification model"
        )
        st.markdown('</div>', unsafe_allow_html=True)
        
        # Info section
        st.markdown('<div class="sidebar-section">', unsafe_allow_html=True)
        st.markdown('<h3 class="section-header">ℹ️ How It Works</h3>', unsafe_allow_html=True)
        st.markdown("""
        <div style="font-size: 0.9rem; color: #5a6c7d;">
        1. <strong>Upload</strong> your baby's cry recording<br>
        2. <strong>AI Analysis</strong> identifies cry type<br>
        3. <strong>Personalized Tips</strong> based on analysis<br>
        4. <strong>Expert Guidance</strong> for next steps
        </div>
        """, unsafe_allow_html=True)
        st.markdown('</div>', unsafe_allow_html=True)
    
    # Main content area
    col1, col2 = st.columns([2, 1])
    
    with col1:
        # Upload section
        st.markdown('<div class="upload-section">', unsafe_allow_html=True)
        st.markdown('<h3 style="color: #2c3e50; margin-bottom: 1rem;">🎵 Upload Baby Cry Audio</h3>', unsafe_allow_html=True)
        uploaded_file = st.file_uploader(
            "Choose an audio file", 
            type=["wav", "mp3", "m4a", "ogg"],
            help="Upload a clear recording of your baby's cry (WAV format recommended for best results)"
        )
        st.markdown('</div>', unsafe_allow_html=True)
        
        # Track conversation history
        if 'chat_history' not in st.session_state:
            st.session_state.chat_history = []
        
        # Analysis button
        if st.button("🔍 Analyze Baby Cry"):
            if uploaded_file is not None:
                # Save the uploaded file temporarily
                temp_file_path = "temp_audio_file.wav"
                with open(temp_file_path, "wb") as f:
                    f.write(uploaded_file.getbuffer())
                
                try:
                    # Load the model
                    with st.spinner("🔄 Loading AI model..."):
                        model_info = load_model(model_path)
                    
                    # Analyze the audio
                    with st.spinner("🎯 Analyzing baby cry patterns..."):
                        result = predict_baby_cry(temp_file_path, model_info)
                    
                    if isinstance(result, dict):
                        # Display prediction results
                        st.markdown('<div class="success-card">', unsafe_allow_html=True)
                        st.success("✅ Analysis Complete!")
                        st.markdown('</div>', unsafe_allow_html=True)
                        
                        # Results section
                        st.markdown('<div class="info-card">', unsafe_allow_html=True)
                        st.markdown('<h3 class="section-header">📊 Analysis Results</h3>', unsafe_allow_html=True)
                        
                        # Metrics
                        col_a, col_b = st.columns(2)
                        with col_a:
                            st.markdown(f"""
                            <div class="metric-container">
                                <div class="metric-value">{result['class']}</div>
                                <div class="metric-label">Cry Type</div>
                            </div>
                            """, unsafe_allow_html=True)
                        
                        with col_b:
                            st.markdown(f"""
                            <div class="metric-container">
                                <div class="metric-value">{result['confidence']:.1f}%</div>
                                <div class="metric-label">Confidence</div>
                            </div>
                            """, unsafe_allow_html=True)
                        
                        st.markdown('</div>', unsafe_allow_html=True)
                        
                        # Probability breakdown
                        st.markdown('<div class="info-card">', unsafe_allow_html=True)
                        st.markdown('<h3 class="section-header">📈 Probability Breakdown</h3>', unsafe_allow_html=True)
                        
                        for class_name, prob in result['probabilities'].items():
                            st.markdown(f"""
                            <div class="probability-item">
                                <strong>{class_name}:</strong> {prob:.1f}%
                            </div>
                            """, unsafe_allow_html=True)
                        
                        st.markdown('</div>', unsafe_allow_html=True)
                        
                        # Collect baby info for context
                        baby_info = {
                            "age": baby_age,
                            "recent_feedings": recent_feedings,
                            "sleep_pattern": sleep_pattern
                        }
                        
                        # Generate and display recommendations
                        st.markdown('<div class="recommendation-card">', unsafe_allow_html=True)
                        st.markdown('<h3 class="section-header">💡 Personalized Recommendations</h3>', unsafe_allow_html=True)
                        
                        with st.spinner("🤖 Generating personalized care recommendations..."):
                            recommendations = generate_care_tips(
                                result['class'], 
                                baby_info=baby_info,
                                chat_history=st.session_state.chat_history
                            )
                        
                        st.markdown(recommendations)
                        st.markdown('</div>', unsafe_allow_html=True)
                        
                        # Add to chat history
                        st.session_state.chat_history.append(f"Cry type: {result['class']}")
                        st.session_state.chat_history.append(f"Recommendation provided: {recommendations[:100]}...")
                        
                        # Keep history manageable
                        if len(st.session_state.chat_history) > 10:
                            st.session_state.chat_history = st.session_state.chat_history[-10:]
                        
                    else:
                        st.error(f"❌ Error: {result}")
                    
                    # Clean up the temporary file
                    if os.path.exists(temp_file_path):
                        os.remove(temp_file_path)
                        
                except Exception as e:
                    st.error(f"❌ Error during analysis: {str(e)}")
                    # Clean up the temporary file
                    if os.path.exists(temp_file_path):
                        os.remove(temp_file_path)
            else:
                st.warning("⚠️ Please upload an audio file to analyze.")
    
    with col2:
        # Session history
        if st.session_state.chat_history:
            st.markdown('<div class="info-card">', unsafe_allow_html=True)
            st.markdown('<h3 class="section-header">📝 Session History</h3>', unsafe_allow_html=True)
            
            for i, message in enumerate(st.session_state.chat_history):
                st.markdown(f"""
                <div class="history-item">
                    <strong>{i+1}.</strong> {message}
                </div>
                """, unsafe_allow_html=True)
            
            st.markdown('</div>', unsafe_allow_html=True)
        
        # Tips section
        st.markdown('<div class="info-card">', unsafe_allow_html=True)
        st.markdown('<h3 class="section-header">💡 Quick Tips</h3>', unsafe_allow_html=True)
        st.markdown("""
        <div style="font-size: 0.9rem; color: #5a6c7d;">
        • <strong>Clear Recording:</strong> Ensure minimal background noise<br>
        • <strong>Duration:</strong> 3-10 seconds of clear crying<br>
        • <strong>Context:</strong> Fill in baby information for better analysis<br>
        • <strong>Emergency:</strong> If baby seems unwell, contact pediatrician immediately
        </div>
        """, unsafe_allow_html=True)
        st.markdown('</div>', unsafe_allow_html=True)

# Run the app
if __name__ == "__main__":
    main()
